"""Unit tests for Issue #1: Core Data Models, Settings, and SQLite Persistence Layer."""

from __future__ import annotations

import pytest
import pytest_asyncio

from app.config import Settings
from app.db import Database
from app.models import (
    CustomRegisterCreate,
    FieldDiff,
    FollowUpCreate,
    PatientRecordCreate,
    PatientRecordUpdate,
    RegisterFieldCreate,
    VisitDraft,
    VisitRecordCreate,
    WsDangerEscalationEvent,
    WsFormUpdateEvent,
)


@pytest_asyncio.fixture
async def db():
    """Provide an isolated in-memory SQLite database for each test."""
    database = Database(db_path=":memory:")
    await database.connect()
    try:
        yield database
    finally:
        await database.close()


def test_settings_paths():
    settings = Settings(database_url="sqlite+aiosqlite:///test_local.db")
    assert settings.sqlite_path.endswith("backend/test_local.db")
    assert settings.danger_signs_path.exists()

    mem_settings = Settings(database_url="sqlite+aiosqlite:///:memory:")
    assert mem_settings.sqlite_path == ":memory:"


def test_ws_protocol_models_serialization():
    draft = VisitDraft(
        beneficiary_id="BEN-001",
        beneficiary_name="Sunita Devi",
        visit_type="ANC",
        weight_kg=2.8,
        vaccinations_due=["TT2"],
    )
    diff = FieldDiff(field="weight_kg", old_value=3.2, new_value=2.8)
    event = WsFormUpdateEvent(
        status="AWAITING_CONFIRMATION",
        record=draft,
        field_diff=diff,
        field_diffs=[diff],
        spoken_reply="Updated weight to 2.8 kilo. Shall I save?",
    )
    payload = event.model_dump()
    assert payload["type"] == "form_update"
    assert payload["field_diff"]["new_value"] == 2.8

    alert = WsDangerEscalationEvent(
        code="DS_MAT_BLEEDING",
        name="Vaginal bleeding during pregnancy",
        priority="IMMEDIATE_EMERGENCY",
        localized_instruction="Refer to PHC immediately.",
    )
    assert alert.model_dump()["type"] == "danger_escalation"


async def test_seed_default_data_is_idempotent(db: Database):
    first_run = await db.seed_default_data()
    second_run = await db.seed_default_data()

    assert first_run is True
    assert second_run is False

    registers = await db.list_registers()
    assert len(registers) == 2
    assert {r.id for r in registers} == {"REG-ANC-DEFAULT", "REG-NB-DEFAULT"}

    patients = await db.list_patients()
    assert len(patients) == 8
    assert {p.id for p in patients} == {f"BEN-00{i}" for i in range(1, 9)}

    sunita = await db.get_patient("BEN-001")
    assert sunita is not None
    assert sunita.patient_name == "Sunita Devi"
    assert sunita.field_answers["Blood Group"] == "B+"

    follow_ups = await db.list_follow_ups()
    assert len(follow_ups) == 4


async def test_custom_register_dynamic_fields_and_patient_search(db: Database):
    await db.seed_default_data()

    reg = await db.create_register(
        CustomRegisterCreate(
            name="High-Risk Pregnancy Survey",
            description="Custom survey for village Ward 4",
            fields=[
                RegisterFieldCreate(field_name="Age", field_type="NUMBER", order_index=0),
                RegisterFieldCreate(field_name="Diabetes History", field_type="BOOLEAN", order_index=1),
                RegisterFieldCreate(field_name="House No", field_type="TEXT", order_index=2),
            ],
        )
    )
    assert reg.id.startswith("REG-")
    assert len(reg.fields) == 3

    added_field = await db.add_register_field(
        reg.id,
        RegisterFieldCreate(field_name="Blood Group", field_type="TEXT"),
    )
    assert added_field.order_index == 3

    fetched = await db.get_register(reg.id)
    assert fetched is not None
    assert [f.field_name for f in fetched.fields] == [
        "Age",
        "Diabetes History",
        "House No",
        "Blood Group",
    ]

    patient = await db.create_patient(
        PatientRecordCreate(
            register_id=reg.id,
            patient_name="Radha Bai",
            category="ANC",
            field_answers={
                "Age": 27,
                "Diabetes History": True,
                "House No": "42",
                "Blood Group": "O+",
            },
        )
    )
    assert patient.id == "BEN-009"

    updated = await db.update_patient(
        patient.id,
        PatientRecordUpdate(field_answers={"BP Reading": "140/90"}),
    )
    assert updated is not None
    assert updated.field_answers["Blood Group"] == "O+"
    assert updated.field_answers["BP Reading"] == "140/90"

    scoped_hits = await db.search_patients_by_name("radha", register_id=reg.id)
    assert len(scoped_hits) == 1
    assert scoped_hits[0].id == "BEN-009"

    wrong_reg_hits = await db.search_patients_by_name("radha", register_id="REG-ANC-DEFAULT")
    assert wrong_reg_hits == []

    global_hits = await db.search_patients_by_name("sunita")
    assert len(global_hits) == 1
    assert global_hits[0].id == "BEN-001"


async def test_visits_and_follow_up_persistence(db: Database):
    await db.seed_default_data()

    routine = await db.create_visit(
        VisitRecordCreate(
            beneficiary_id="BEN-001",
            beneficiary_name="Sunita Devi",
            visit_type="ANC",
            weight_kg=54.5,
            vaccinations_due=["TT2"],
            next_visit_date="2026-12-19",
            ifa_tablets_given=30,
            notes="Routine ANC checkup, IFA goli given",
            raw_transcript="Sunita Devi ANC checkup weight 54.5 kilo IFA goli 30",
            language_code="hi-IN",
        )
    )
    assert routine.id.startswith("VIS-")
    assert routine.has_danger_sign is False

    urgent = await db.create_visit(
        VisitRecordCreate(
            beneficiary_id="BEN-004",
            beneficiary_name="Kavitha Reddy (Baby)",
            visit_type="NEWBORN",
            weight_kg=2.4,
            danger_signs=["DS_NB_HIGH_FEVER_COLD"],
            notes="Newborn high fever reported; referred to PHC",
            raw_transcript="Kavitha baby high fever",
            language_code="kn-IN",
        )
    )
    assert urgent.has_danger_sign is True

    all_visits = await db.list_visits()
    assert len(all_visits) == 2

    danger_visits = await db.list_visits(only_danger=True)
    assert len(danger_visits) == 1
    assert danger_visits[0].id == urgent.id

    fup = await db.create_follow_up(
        FollowUpCreate(
            beneficiary_id="BEN-001",
            beneficiary_name="Sunita Devi",
            visit_id=routine.id,
            due_date="2026-12-19",
            purpose="Next ANC visit & TT2 verification",
            vaccines_due=["TT2"],
        )
    )
    assert fup.status == "PENDING"

    completed = await db.update_follow_up_status(fup.id, "COMPLETED")
    assert completed is not None
    assert completed.status == "COMPLETED"

    completed_list = await db.list_follow_ups(status="COMPLETED")
    assert len(completed_list) == 1
    assert completed_list[0].id == fup.id

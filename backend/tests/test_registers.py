"""Tests for Issue #2: Dynamic Custom Register Builder, Search, Profile View, and Excel/CSV Export."""

from __future__ import annotations

import csv
import io

import pytest
import pytest_asyncio
from fastapi import FastAPI
from fastapi.testclient import TestClient
from openpyxl import load_workbook

from app.db import Database
from app.models import (
    CustomRegisterCreate,
    PatientRecordCreate,
    PatientRecordUpdate,
    RegisterFieldCreate,
    VisitRecordCreate,
)
from app.routers.registers import router as registers_router
from app.services.register_service import (
    DuplicateFieldError,
    PatientNotFoundError,
    RegisterNotFoundError,
    RegisterService,
)


@pytest_asyncio.fixture
async def service():
    db = Database(db_path=":memory:")
    await db.connect()
    await db.seed_default_data()
    svc = RegisterService(db)
    try:
        yield svc
    finally:
        await db.close()


async def test_create_register_deduplicates_fields_and_blocks_duplicates(service: RegisterService):
    reg = await service.create_register(
        CustomRegisterCreate(
            name="TB & Nutrition Survey",
            description="Village house-to-house screening",
            fields=[
                RegisterFieldCreate(field_name="Age", field_type="NUMBER"),
                RegisterFieldCreate(field_name="age", field_type="NUMBER"),  # duplicate ignored
                RegisterFieldCreate(field_name="Cough Duration", field_type="TEXT"),
            ],
        )
    )
    assert [f.field_name for f in reg.fields] == ["Age", "Cough Duration"]

    added = await service.add_field(reg.id, RegisterFieldCreate(field_name="Weight (kg)", field_type="NUMBER"))
    assert added.field_name == "Weight (kg)"

    with pytest.raises(DuplicateFieldError):
        await service.add_field(reg.id, RegisterFieldCreate(field_name="cough duration"))


async def test_patient_intake_auto_adds_spoken_fields_and_builds_profile_view(service: RegisterService):
    reg = await service.create_register(
        CustomRegisterCreate(
            name="First Visit Maternal Intake",
            fields=[
                RegisterFieldCreate(field_name="Age", field_type="NUMBER"),
                RegisterFieldCreate(field_name="Medical History", field_type="TEXT"),
            ],
        )
    )

    patient = await service.add_patient(
        PatientRecordCreate(
            register_id=reg.id,
            patient_name="सुनीता बाई (Sunita Bai)",
            category="ANC",
            field_answers={
                "age": 25,  # matches canonical "Age" case-insensitively
                "Medical History": "पिछली बार हाई बीपी था",
                "Blood Group": "O+",  # brand-new field spoken on the fly
                "LMP": "2026-09-10",  # brand-new DATE field
            },
        )
    )
    assert patient.field_answers["Age"] == 25
    assert "age" not in patient.field_answers

    # Newly spoken fields should now be registered on the register schema
    refreshed_reg = await service.get_register(reg.id)
    field_types = {f.field_name: f.field_type for f in refreshed_reg.fields}
    assert field_types["Blood Group"] == "TEXT"
    assert field_types["LMP"] == "DATE"

    # Mobile-friendly Field: Answer profile view respects register field ordering
    profile = await service.get_patient_profile_view(patient.id)
    assert profile.patient_name == "सुनीता बाई (Sunita Bai)"
    assert profile.register_name == "First Visit Maternal Intake"
    assert [(i.field_name, i.answer) for i in profile.items] == [
        ("Age", 25),
        ("Medical History", "पिछली बार हाई बीपी था"),
        ("Blood Group", "O+"),
        ("LMP", "2026-09-10"),
    ]


async def test_search_patients_in_register_and_followup_context(service: RegisterService):
    hits = await service.search_patients(register_id="REG-ANC-DEFAULT", query="anitha")
    assert len(hits) == 1
    assert hits[0].id == "BEN-005"

    # Patients in the newborn register do not appear in ANC register search
    no_hits = await service.search_patients(register_id="REG-ANC-DEFAULT", query="lakshmi")
    assert no_hits == []

    # Empty query lists all 4 ANC patients
    all_anc = await service.search_patients(register_id="REG-ANC-DEFAULT", query="")
    assert len(all_anc) == 4

    # Log a visit for BEN-005 and verify follow-up context includes baseline + last visit
    await service.db.create_visit(
        VisitRecordCreate(
            beneficiary_id="BEN-005",
            beneficiary_name="Anitha Sharma",
            visit_type="ANC",
            weight_kg=58.2,
            vaccinations_due=["TT2"],
            next_visit_date="2026-12-19",
        )
    )
    ctx = await service.build_followup_context("BEN-005")
    assert ctx["beneficiary_id"] == "BEN-005"
    assert ctx["baseline_fields"]["Medical History"] == "Prior C-section, monitor BP closely"
    assert ctx["last_visit"]["weight_kg"] == 58.2

    with pytest.raises(RegisterNotFoundError):
        await service.search_patients(register_id="REG-MISSING", query="sunita")

    with pytest.raises(PatientNotFoundError):
        await service.get_patient_profile_view("BEN-999")


async def test_export_csv_and_xlsx_preserve_dynamic_columns_and_indic_text(service: RegisterService):
    await service.add_patient(
        PatientRecordCreate(
            register_id="REG-ANC-DEFAULT",
            patient_name="ಕಾವ್ಯ ದೇವಿ",
            category="ANC",
            field_answers={
                "Age": 23,
                "Parity": "G1P0",
                "Medical History": "ಸಾಮಾನ್ಯ (Normal)",
                "Allergies": "None",
            },
        )
    )

    # 1. Verify CSV export (UTF-8 with BOM for Excel Indic compatibility)
    csv_filename, csv_bytes = await service.export_register_csv("REG-ANC-DEFAULT")
    assert csv_filename == "anc_first_intake_register.csv"
    assert csv_bytes.startswith(b"\xef\xbb\xbf")

    decoded = csv_bytes.decode("utf-8-sig")
    parsed_rows = list(csv.reader(io.StringIO(decoded)))
    headers = parsed_rows[0]
    assert headers[:3] == ["Patient ID", "Patient Name", "Category"]
    assert "Medical History" in headers
    assert "Allergies" in headers
    assert len(parsed_rows) == 6  # 1 header + 4 seeded ANC + 1 newly added Kannada patient

    kavya_row = next(r for r in parsed_rows[1:] if r[1] == "ಕಾವ್ಯ ದೇವಿ")
    assert kavya_row[headers.index("Medical History")] == "ಸಾಮಾನ್ಯ (Normal)"
    assert kavya_row[headers.index("Allergies")] == "None"

    # 2. Verify Excel (.xlsx) export
    xlsx_filename, xlsx_bytes = await service.export_register_xlsx("REG-ANC-DEFAULT")
    assert xlsx_filename == "anc_first_intake_register.xlsx"

    wb = load_workbook(io.BytesIO(xlsx_bytes))
    ws = wb.active
    assert ws is not None
    assert ws.title == "ANC First Intake Register"
    assert ws.freeze_panes == "A2"

    xlsx_headers = [cell.value for cell in ws[1]]
    assert xlsx_headers == headers
    assert ws.max_row == 6

    names = [ws.cell(row=r, column=2).value for r in range(2, ws.max_row + 1)]
    assert "Sunita Devi" in names
    assert "ಕಾವ್ಯ ದೇವಿ" in names


def test_register_rest_endpoints_end_to_end():
    """Verify FastAPI router HTTP endpoints including search, profile view, and exports."""
    from contextlib import asynccontextmanager

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        db = Database(db_path=":memory:")
        await db.connect()
        await db.seed_default_data()
        app.state.register_service = RegisterService(db)
        yield
        await db.close()

    app = FastAPI(lifespan=lifespan)
    app.include_router(registers_router)

    with TestClient(app) as client:
        # List starter registers
        resp = client.get("/api/registers")
        assert resp.status_code == 200
        assert len(resp.json()) == 2

        # Create a custom register
        create_resp = client.post(
            "/api/registers",
            json={
                "name": "Immunisation Drive",
                "description": "Ward 5 polio & pentavalent camp",
                "fields": [
                    {"field_name": "Child Age (Months)", "field_type": "NUMBER"},
                    {"field_name": "Mother Name", "field_type": "TEXT"},
                ],
            },
        )
        assert create_resp.status_code == 201
        reg_id = create_resp.json()["id"]

        # Add a patient to that register
        pat_resp = client.post(
            f"/api/registers/{reg_id}/patients",
            json={
                "register_id": reg_id,
                "patient_name": "Rohan Kumar",
                "category": "IMMUNISATION",
                "field_answers": {"Child Age (Months)": 6, "Mother Name": "Geeta Devi"},
            },
        )
        assert pat_resp.status_code == 201
        pat_id = pat_resp.json()["id"]

        # Search patient by name
        search_resp = client.get(f"/api/registers/{reg_id}/patients", params={"q": "rohan"})
        assert search_resp.status_code == 200
        assert len(search_resp.json()) == 1

        # Fetch mobile Field: Answer profile view
        prof_resp = client.get(f"/api/patients/{pat_id}/profile")
        assert prof_resp.status_code == 200
        assert prof_resp.json()["items"][0] == {"field_name": "Child Age (Months)", "answer": 6}

        # Download CSV export
        csv_resp = client.get(f"/api/registers/{reg_id}/export", params={"format": "csv"})
        assert csv_resp.status_code == 200
        assert "immunisation_drive.csv" in csv_resp.headers["content-disposition"]
        assert "Rohan Kumar" in csv_resp.content.decode("utf-8-sig")

        # Download XLSX export
        xlsx_resp = client.get(f"/api/registers/{reg_id}/export", params={"format": "xlsx"})
        assert xlsx_resp.status_code == 200
        assert "immunisation_drive.xlsx" in xlsx_resp.headers["content-disposition"]
        wb = load_workbook(io.BytesIO(xlsx_resp.content))
        assert wb.active.cell(row=2, column=2).value == "Rohan Kumar"


async def test_p406_duplicate_patient_prevention_and_phonetic_upsert():
    """Verify P4-06: 'Savitha Rao' + 'Savita Rao' merge into one updated record with matched_existing=True."""
    from app.services.register_service import normalize_patient_name_key

    assert normalize_patient_name_key("Savitha Rao") == normalize_patient_name_key("Savita Rao")
    assert normalize_patient_name_key("Suneetha Devi") == normalize_patient_name_key("Sunita Devi")
    assert normalize_patient_name_key("Kavitha Reddy") == normalize_patient_name_key("Kavita Reddi")
    assert normalize_patient_name_key("Savitha Rao") != normalize_patient_name_key("Savitha Devi")

    db = Database(":memory:")
    await db.connect()
    await db.seed_default_data()
    svc = RegisterService(db)

    try:
        reg = await svc.create_register(
            CustomRegisterCreate(
                name="ANC Duplicate Guard Test",
                fields=[
                    RegisterFieldCreate(field_name="Age", field_type="NUMBER"),
                    RegisterFieldCreate(field_name="Blood Group", field_type="TEXT"),
                    RegisterFieldCreate(field_name="House / Ward", field_type="TEXT"),
                ],
            )
        )

        # 1. Initial onboarding of Savitha Rao
        p1 = await svc.add_patient(
            PatientRecordCreate(
                register_id=reg.id,
                patient_name="Savitha Rao",
                category="ANC",
                field_answers={"Age": 26, "Blood Group": "O+", "House / Ward": "House 19"},
            )
        )
        assert p1.matched_existing is False

        # 2. Re-adding with phonetic spelling 'Savita Rao' & updated Age merges into p1 (NO duplicate!)
        p2 = await svc.add_patient(
            PatientRecordCreate(
                register_id=reg.id,
                patient_name="  Savita   Rao ",
                category="ANC",
                field_answers={"Age": 28, "Blood Group": ""},  # empty value must NOT wipe existing O+
            )
        )
        assert p2.matched_existing is True
        assert p2.id == p1.id
        assert p2.field_answers["Age"] == 28
        assert p2.field_answers["Blood Group"] == "O+"
        assert p2.field_answers["House / Ward"] == "House 19"

        # 3. Different person with same first name ('Savitha Devi') MUST still create a separate row
        p3 = await svc.add_patient(
            PatientRecordCreate(
                register_id=reg.id,
                patient_name="Savitha Devi",
                category="ANC",
                field_answers={"Age": 24},
            )
        )
        assert p3.matched_existing is False
        assert p3.id != p1.id

        all_patients = await svc.search_patients(reg.id)
        assert len(all_patients) == 2
    finally:
        await db.close()

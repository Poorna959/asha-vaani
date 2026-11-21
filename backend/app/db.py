"""Asynchronous SQLite persistence layer using aiosqlite for ASHA Vaani."""

from __future__ import annotations

import json
import re
import uuid
from typing import Any, Optional

import aiosqlite

from app.config import get_settings
from app.models import (
    CustomRegister,
    CustomRegisterCreate,
    FollowUp,
    FollowUpCreate,
    FollowUpStatus,
    PatientRecord,
    PatientRecordCreate,
    PatientRecordUpdate,
    RegisterField,
    RegisterFieldCreate,
    VisitRecord,
    VisitRecordCreate,
    utc_now_iso,
)


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS custom_registers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS register_fields (
    id TEXT PRIMARY KEY,
    register_id TEXT NOT NULL,
    field_name TEXT NOT NULL,
    field_type TEXT NOT NULL DEFAULT 'TEXT',
    order_index INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (register_id) REFERENCES custom_registers(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS patient_records (
    id TEXT PRIMARY KEY,
    register_id TEXT NOT NULL,
    patient_name TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'ANC',
    field_answers_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (register_id) REFERENCES custom_registers(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS visits (
    id TEXT PRIMARY KEY,
    beneficiary_id TEXT NOT NULL,
    beneficiary_name TEXT NOT NULL,
    visit_type TEXT NOT NULL,
    weight_kg REAL,
    vaccinations_due_json TEXT NOT NULL DEFAULT '[]',
    next_visit_date TEXT,
    ifa_tablets_given INTEGER,
    has_danger_sign INTEGER NOT NULL DEFAULT 0,
    danger_signs_json TEXT NOT NULL DEFAULT '[]',
    notes TEXT NOT NULL DEFAULT '',
    raw_transcript TEXT NOT NULL DEFAULT '',
    language_code TEXT NOT NULL DEFAULT 'hi-IN',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS follow_ups (
    id TEXT PRIMARY KEY,
    beneficiary_id TEXT NOT NULL,
    beneficiary_name TEXT NOT NULL,
    visit_id TEXT,
    due_date TEXT NOT NULL,
    purpose TEXT NOT NULL,
    vaccines_due_json TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TEXT NOT NULL,
    FOREIGN KEY (visit_id) REFERENCES visits(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_register_fields_register
    ON register_fields (register_id, order_index);

CREATE INDEX IF NOT EXISTS idx_patient_records_register_name
    ON patient_records (register_id, patient_name);

CREATE INDEX IF NOT EXISTS idx_visits_beneficiary
    ON visits (beneficiary_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_follow_ups_due_status
    ON follow_ups (due_date, status);
"""


def _short_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:8].upper()}"


class Database:
    """Async SQLite repository for registers, patients, visits, and follow-ups."""

    def __init__(self, db_path: Optional[str] = None) -> None:
        self.db_path = db_path or get_settings().sqlite_path
        self._conn: Optional[aiosqlite.Connection] = None

    async def connect(self) -> None:
        """Open SQLite connection, enable foreign keys, and initialize schema."""
        if self._conn is not None:
            return
        self._conn = await aiosqlite.connect(self.db_path)
        self._conn.row_factory = aiosqlite.Row
        await self._conn.execute("PRAGMA foreign_keys = ON;")
        if self.db_path != ":memory:":
            await self._conn.execute("PRAGMA journal_mode = WAL;")
        await self._conn.executescript(SCHEMA_SQL)
        await self._conn.commit()

    async def close(self) -> None:
        """Close the active SQLite connection."""
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    @property
    def conn(self) -> aiosqlite.Connection:
        if self._conn is None:
            raise RuntimeError("Database is not connected. Call await db.connect() first.")
        return self._conn

    # ------------------------------------------------------------------
    # Custom Registers & Dynamic Fields
    # ------------------------------------------------------------------

    async def create_register(
        self,
        payload: CustomRegisterCreate,
        register_id: Optional[str] = None,
    ) -> CustomRegister:
        """Create a custom register along with its ordered custom fields."""
        reg_id = register_id or _short_id("REG")
        created_at = utc_now_iso()

        await self.conn.execute(
            """
            INSERT INTO custom_registers (id, name, description, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (reg_id, payload.name.strip(), payload.description.strip(), created_at),
        )

        created_fields: list[RegisterField] = []
        for idx, field_in in enumerate(payload.fields):
            order_idx = field_in.order_index if field_in.order_index else idx
            field_id = _short_id("FLD")
            await self.conn.execute(
                """
                INSERT INTO register_fields (id, register_id, field_name, field_type, order_index)
                VALUES (?, ?, ?, ?, ?)
                """,
                (field_id, reg_id, field_in.field_name.strip(), field_in.field_type, order_idx),
            )
            created_fields.append(
                RegisterField(
                    id=field_id,
                    register_id=reg_id,
                    field_name=field_in.field_name.strip(),
                    field_type=field_in.field_type,
                    order_index=order_idx,
                )
            )

        await self.conn.commit()
        return CustomRegister(
            id=reg_id,
            name=payload.name.strip(),
            description=payload.description.strip(),
            fields=created_fields,
            created_at=created_at,
        )

    async def add_register_field(
        self,
        register_id: str,
        field_in: RegisterFieldCreate,
    ) -> RegisterField:
        """Append a new dynamic field to an existing custom register."""
        existing = await self.get_register(register_id)
        if existing is None:
            raise ValueError(f"Register '{register_id}' does not exist.")

        order_idx = field_in.order_index if field_in.order_index else len(existing.fields)
        field_id = _short_id("FLD")
        await self.conn.execute(
            """
            INSERT INTO register_fields (id, register_id, field_name, field_type, order_index)
            VALUES (?, ?, ?, ?, ?)
            """,
            (field_id, register_id, field_in.field_name.strip(), field_in.field_type, order_idx),
        )
        await self.conn.commit()
        return RegisterField(
            id=field_id,
            register_id=register_id,
            field_name=field_in.field_name.strip(),
            field_type=field_in.field_type,
            order_index=order_idx,
        )

    async def _fetch_register_fields(self, register_id: str) -> list[RegisterField]:
        async with self.conn.execute(
            """
            SELECT id, register_id, field_name, field_type, order_index
            FROM register_fields
            WHERE register_id = ?
            ORDER BY order_index ASC, id ASC
            """,
            (register_id,),
        ) as cursor:
            rows = await cursor.fetchall()
        return [
            RegisterField(
                id=row["id"],
                register_id=row["register_id"],
                field_name=row["field_name"],
                field_type=row["field_type"],
                order_index=row["order_index"],
            )
            for row in rows
        ]

    async def get_register(self, register_id: str) -> Optional[CustomRegister]:
        """Fetch a single custom register with its ordered fields."""
        async with self.conn.execute(
            "SELECT id, name, description, created_at FROM custom_registers WHERE id = ?",
            (register_id,),
        ) as cursor:
            row = await cursor.fetchone()
        if row is None:
            return None
        fields = await self._fetch_register_fields(register_id)
        return CustomRegister(
            id=row["id"],
            name=row["name"],
            description=row["description"],
            fields=fields,
            created_at=row["created_at"],
        )

    async def list_registers(self) -> list[CustomRegister]:
        """List all custom registers with their fields."""
        async with self.conn.execute(
            "SELECT id, name, description, created_at FROM custom_registers ORDER BY created_at ASC"
        ) as cursor:
            rows = await cursor.fetchall()

        registers: list[CustomRegister] = []
        for row in rows:
            fields = await self._fetch_register_fields(row["id"])
            registers.append(
                CustomRegister(
                    id=row["id"],
                    name=row["name"],
                    description=row["description"],
                    fields=fields,
                    created_at=row["created_at"],
                )
            )
        return registers

    # ------------------------------------------------------------------
    # Patient Profiles (Custom Intake Rows)
    # ------------------------------------------------------------------

    async def _next_beneficiary_id(self) -> str:
        """Generate sequential human-friendly beneficiary IDs like BEN-009."""
        async with self.conn.execute("SELECT id FROM patient_records") as cursor:
            rows = await cursor.fetchall()
        max_num = 0
        for row in rows:
            match = re.fullmatch(r"BEN-(\d+)", row["id"])
            if match:
                max_num = max(max_num, int(match.group(1)))
        return f"BEN-{max_num + 1:03d}"

    def _row_to_patient(self, row: aiosqlite.Row) -> PatientRecord:
        return PatientRecord(
            id=row["id"],
            register_id=row["register_id"],
            patient_name=row["patient_name"],
            category=row["category"],
            field_answers=json.loads(row["field_answers_json"]),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    async def create_patient(self, payload: PatientRecordCreate) -> PatientRecord:
        """Create a new patient profile inside a custom register."""
        patient_id = payload.id or await self._next_beneficiary_id()
        now = utc_now_iso()
        await self.conn.execute(
            """
            INSERT INTO patient_records (
                id, register_id, patient_name, category, field_answers_json, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                patient_id,
                payload.register_id,
                payload.patient_name.strip(),
                payload.category,
                json.dumps(payload.field_answers, ensure_ascii=False),
                now,
                now,
            ),
        )
        await self.conn.commit()
        return PatientRecord(
            id=patient_id,
            register_id=payload.register_id,
            patient_name=payload.patient_name.strip(),
            category=payload.category,
            field_answers=dict(payload.field_answers),
            created_at=now,
            updated_at=now,
        )

    async def get_patient(self, patient_id: str) -> Optional[PatientRecord]:
        """Fetch a single patient profile by ID."""
        async with self.conn.execute(
            "SELECT * FROM patient_records WHERE id = ?",
            (patient_id,),
        ) as cursor:
            row = await cursor.fetchone()
        return self._row_to_patient(row) if row else None

    async def update_patient(
        self,
        patient_id: str,
        payload: PatientRecordUpdate,
    ) -> Optional[PatientRecord]:
        """Patch a patient's name, category, or merge dynamic field answers."""
        existing = await self.get_patient(patient_id)
        if existing is None:
            return None

        new_name = payload.patient_name.strip() if payload.patient_name else existing.patient_name
        new_category = payload.category or existing.category
        merged_answers: dict[str, Any] = dict(existing.field_answers)
        if payload.field_answers:
            merged_answers.update(payload.field_answers)
        updated_at = utc_now_iso()

        await self.conn.execute(
            """
            UPDATE patient_records
            SET patient_name = ?, category = ?, field_answers_json = ?, updated_at = ?
            WHERE id = ?
            """,
            (
                new_name,
                new_category,
                json.dumps(merged_answers, ensure_ascii=False),
                updated_at,
                patient_id,
            ),
        )
        await self.conn.commit()
        return PatientRecord(
            id=existing.id,
            register_id=existing.register_id,
            patient_name=new_name,
            category=new_category,
            field_answers=merged_answers,
            created_at=existing.created_at,
            updated_at=updated_at,
        )

    async def list_patients(self, register_id: Optional[str] = None) -> list[PatientRecord]:
        """List all patients, optionally scoped to a specific custom register."""
        if register_id:
            sql = """
                SELECT * FROM patient_records
                WHERE register_id = ?
                ORDER BY patient_name COLLATE NOCASE ASC
            """
            params: tuple[Any, ...] = (register_id,)
        else:
            sql = "SELECT * FROM patient_records ORDER BY patient_name COLLATE NOCASE ASC"
            params = ()

        async with self.conn.execute(sql, params) as cursor:
            rows = await cursor.fetchall()
        return [self._row_to_patient(row) for row in rows]

    async def search_patients_by_name(
        self,
        query: str,
        register_id: Optional[str] = None,
    ) -> list[PatientRecord]:
        """Case-insensitive substring search by patient name (or ID), optionally scoped to a register."""
        cleaned = query.strip()
        if not cleaned:
            return await self.list_patients(register_id=register_id)

        like_pattern = f"%{cleaned}%"
        if register_id:
            sql = """
                SELECT * FROM patient_records
                WHERE register_id = ?
                  AND (patient_name LIKE ? COLLATE NOCASE OR id LIKE ? COLLATE NOCASE)
                ORDER BY patient_name COLLATE NOCASE ASC
            """
            params: tuple[Any, ...] = (register_id, like_pattern, like_pattern)
        else:
            sql = """
                SELECT * FROM patient_records
                WHERE patient_name LIKE ? COLLATE NOCASE OR id LIKE ? COLLATE NOCASE
                ORDER BY patient_name COLLATE NOCASE ASC
            """
            params = (like_pattern, like_pattern)

        async with self.conn.execute(sql, params) as cursor:
            rows = await cursor.fetchall()
        return [self._row_to_patient(row) for row in rows]

    # ------------------------------------------------------------------
    # Visits
    # ------------------------------------------------------------------

    def _row_to_visit(self, row: aiosqlite.Row) -> VisitRecord:
        return VisitRecord(
            id=row["id"],
            beneficiary_id=row["beneficiary_id"],
            beneficiary_name=row["beneficiary_name"],
            visit_type=row["visit_type"],
            weight_kg=row["weight_kg"],
            vaccinations_due=json.loads(row["vaccinations_due_json"]),
            next_visit_date=row["next_visit_date"],
            ifa_tablets_given=row["ifa_tablets_given"],
            has_danger_sign=bool(row["has_danger_sign"]),
            danger_signs=json.loads(row["danger_signs_json"]),
            notes=row["notes"],
            raw_transcript=row["raw_transcript"],
            language_code=row["language_code"],
            created_at=row["created_at"],
        )

    async def create_visit(self, payload: VisitRecordCreate) -> VisitRecord:
        """Persist a confirmed structured home-visit record."""
        visit_id = _short_id("VIS")
        created_at = utc_now_iso()
        has_danger = bool(payload.has_danger_sign or payload.danger_signs)

        await self.conn.execute(
            """
            INSERT INTO visits (
                id, beneficiary_id, beneficiary_name, visit_type, weight_kg,
                vaccinations_due_json, next_visit_date, ifa_tablets_given,
                has_danger_sign, danger_signs_json, notes, raw_transcript,
                language_code, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                visit_id,
                payload.beneficiary_id,
                payload.beneficiary_name.strip(),
                payload.visit_type,
                payload.weight_kg,
                json.dumps(payload.vaccinations_due, ensure_ascii=False),
                payload.next_visit_date,
                payload.ifa_tablets_given,
                1 if has_danger else 0,
                json.dumps(payload.danger_signs, ensure_ascii=False),
                payload.notes,
                payload.raw_transcript,
                payload.language_code,
                created_at,
            ),
        )
        await self.conn.commit()
        return VisitRecord(
            id=visit_id,
            beneficiary_id=payload.beneficiary_id,
            beneficiary_name=payload.beneficiary_name.strip(),
            visit_type=payload.visit_type,
            weight_kg=payload.weight_kg,
            vaccinations_due=list(payload.vaccinations_due),
            next_visit_date=payload.next_visit_date,
            ifa_tablets_given=payload.ifa_tablets_given,
            has_danger_sign=has_danger,
            danger_signs=list(payload.danger_signs),
            notes=payload.notes,
            raw_transcript=payload.raw_transcript,
            language_code=payload.language_code,
            created_at=created_at,
        )

    async def get_visit(self, visit_id: str) -> Optional[VisitRecord]:
        """Fetch a single persisted visit by ID."""
        async with self.conn.execute(
            "SELECT * FROM visits WHERE id = ?",
            (visit_id,),
        ) as cursor:
            row = await cursor.fetchone()
        return self._row_to_visit(row) if row else None

    async def list_visits(
        self,
        beneficiary_id: Optional[str] = None,
        only_danger: bool = False,
    ) -> list[VisitRecord]:
        """List saved home-visit records, newest first."""
        clauses: list[str] = []
        params: list[Any] = []
        if beneficiary_id:
            clauses.append("beneficiary_id = ?")
            params.append(beneficiary_id)
        if only_danger:
            clauses.append("has_danger_sign = 1")

        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        sql = f"SELECT * FROM visits {where_sql} ORDER BY created_at DESC"

        async with self.conn.execute(sql, tuple(params)) as cursor:
            rows = await cursor.fetchall()
        return [self._row_to_visit(row) for row in rows]

    # ------------------------------------------------------------------
    # Follow-Up Queue
    # ------------------------------------------------------------------

    def _row_to_follow_up(self, row: aiosqlite.Row) -> FollowUp:
        return FollowUp(
            id=row["id"],
            beneficiary_id=row["beneficiary_id"],
            beneficiary_name=row["beneficiary_name"],
            visit_id=row["visit_id"],
            due_date=row["due_date"],
            purpose=row["purpose"],
            vaccines_due=json.loads(row["vaccines_due_json"]),
            status=row["status"],
            created_at=row["created_at"],
        )

    async def create_follow_up(self, payload: FollowUpCreate) -> FollowUp:
        """Add an entry to the ASHA follow-up queue."""
        fup_id = _short_id("FUP")
        created_at = utc_now_iso()
        await self.conn.execute(
            """
            INSERT INTO follow_ups (
                id, beneficiary_id, beneficiary_name, visit_id,
                due_date, purpose, vaccines_due_json, status, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                fup_id,
                payload.beneficiary_id,
                payload.beneficiary_name.strip(),
                payload.visit_id,
                payload.due_date,
                payload.purpose.strip(),
                json.dumps(payload.vaccines_due, ensure_ascii=False),
                payload.status,
                created_at,
            ),
        )
        await self.conn.commit()
        return FollowUp(
            id=fup_id,
            beneficiary_id=payload.beneficiary_id,
            beneficiary_name=payload.beneficiary_name.strip(),
            visit_id=payload.visit_id,
            due_date=payload.due_date,
            purpose=payload.purpose.strip(),
            vaccines_due=list(payload.vaccines_due),
            status=payload.status,
            created_at=created_at,
        )

    async def list_follow_ups(
        self,
        status: Optional[FollowUpStatus] = None,
        beneficiary_id: Optional[str] = None,
    ) -> list[FollowUp]:
        """List follow-up queue entries ordered by due date."""
        clauses: list[str] = []
        params: list[Any] = []
        if status:
            clauses.append("status = ?")
            params.append(status)
        if beneficiary_id:
            clauses.append("beneficiary_id = ?")
            params.append(beneficiary_id)

        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        sql = f"SELECT * FROM follow_ups {where_sql} ORDER BY due_date ASC, created_at DESC"

        async with self.conn.execute(sql, tuple(params)) as cursor:
            rows = await cursor.fetchall()
        return [self._row_to_follow_up(row) for row in rows]

    async def update_follow_up_status(
        self,
        follow_up_id: str,
        status: FollowUpStatus,
    ) -> Optional[FollowUp]:
        """Update status of a follow-up item (PENDING | COMPLETED | ESCALATED)."""
        await self.conn.execute(
            "UPDATE follow_ups SET status = ? WHERE id = ?",
            (status, follow_up_id),
        )
        await self.conn.commit()
        async with self.conn.execute(
            "SELECT * FROM follow_ups WHERE id = ?",
            (follow_up_id,),
        ) as cursor:
            row = await cursor.fetchone()
        return self._row_to_follow_up(row) if row else None

    # ------------------------------------------------------------------
    # Idempotent Seed Village Roster (8 Realistic Beneficiaries)
    # ------------------------------------------------------------------

    async def seed_default_data(self) -> bool:
        """Seed starter registers, 8 village beneficiaries, and sample follow-ups once.

        Returns True if seed data was inserted, False if already seeded.
        """
        async with self.conn.execute("SELECT COUNT(*) AS cnt FROM custom_registers") as cursor:
            row = await cursor.fetchone()
        if row and row["cnt"] > 0:
            return False

        anc_reg = await self.create_register(
            CustomRegisterCreate(
                name="ANC First Intake Register",
                description="Baseline maternal health registration for pregnant women in Ramanagara Ward 2",
                fields=[
                    RegisterFieldCreate(field_name="Age", field_type="NUMBER", order_index=0),
                    RegisterFieldCreate(field_name="Parity", field_type="TEXT", order_index=1),
                    RegisterFieldCreate(field_name="LMP", field_type="DATE", order_index=2),
                    RegisterFieldCreate(field_name="Blood Group", field_type="TEXT", order_index=3),
                    RegisterFieldCreate(field_name="Medical History", field_type="TEXT", order_index=4),
                    RegisterFieldCreate(field_name="Phone", field_type="TEXT", order_index=5),
                    RegisterFieldCreate(field_name="House / Ward", field_type="TEXT", order_index=6),
                ],
            ),
            register_id="REG-ANC-DEFAULT",
        )

        nb_reg = await self.create_register(
            CustomRegisterCreate(
                name="Newborn & Immunisation Master",
                description="Home-Based Newborn Care (HBNC) & infant immunisation tracking roster",
                fields=[
                    RegisterFieldCreate(field_name="Date of Birth", field_type="DATE", order_index=0),
                    RegisterFieldCreate(field_name="Birth Weight (kg)", field_type="NUMBER", order_index=1),
                    RegisterFieldCreate(field_name="Mother Name", field_type="TEXT", order_index=2),
                    RegisterFieldCreate(field_name="Delivery Place", field_type="TEXT", order_index=3),
                    RegisterFieldCreate(field_name="Medical History", field_type="TEXT", order_index=4),
                    RegisterFieldCreate(field_name="House / Ward", field_type="TEXT", order_index=5),
                ],
            ),
            register_id="REG-NB-DEFAULT",
        )

        seed_patients = [
            PatientRecordCreate(
                id="BEN-001",
                register_id=anc_reg.id,
                patient_name="Sunita Devi",
                category="ANC",
                field_answers={
                    "Age": 24,
                    "Parity": "G2P1",
                    "LMP": "2026-04-15",
                    "Blood Group": "B+",
                    "Medical History": "Mild anemia, TT1 completed",
                    "Phone": "9845011101",
                    "House / Ward": "House 14, Ward 2",
                },
            ),
            PatientRecordCreate(
                id="BEN-002",
                register_id=nb_reg.id,
                patient_name="Lakshmi Gowda (Baby)",
                category="NEWBORN",
                field_answers={
                    "Date of Birth": "2026-11-14",
                    "Birth Weight (kg)": 2.9,
                    "Mother Name": "Lakshmi Gowda",
                    "Delivery Place": "PHC Ramanagara",
                    "Medical History": "Normal institutional delivery",
                    "House / Ward": "House 07, Ward 1",
                },
            ),
            PatientRecordCreate(
                id="BEN-003",
                register_id=anc_reg.id,
                patient_name="Meena Kumari",
                category="ANC",
                field_answers={
                    "Age": 22,
                    "Parity": "G1P0",
                    "LMP": "2026-08-20",
                    "Blood Group": "O+",
                    "Medical History": "No prior complications, IFA started",
                    "Phone": "9845011103",
                    "House / Ward": "House 21, Ward 2",
                },
            ),
            PatientRecordCreate(
                id="BEN-004",
                register_id=nb_reg.id,
                patient_name="Kavitha Reddy (Baby)",
                category="NEWBORN",
                field_answers={
                    "Date of Birth": "2026-11-07",
                    "Birth Weight (kg)": 2.3,
                    "Mother Name": "Kavitha Reddy",
                    "Delivery Place": "District Hospital",
                    "Medical History": "Low birth weight monitoring",
                    "House / Ward": "House 03, Ward 3",
                },
            ),
            PatientRecordCreate(
                id="BEN-005",
                register_id=anc_reg.id,
                patient_name="Anitha Sharma",
                category="ANC",
                field_answers={
                    "Age": 29,
                    "Parity": "G3P2",
                    "LMP": "2026-05-02",
                    "Blood Group": "A+",
                    "Medical History": "Prior C-section, monitor BP closely",
                    "Phone": "9845011105",
                    "House / Ward": "House 18, Ward 1",
                },
            ),
            PatientRecordCreate(
                id="BEN-006",
                register_id=nb_reg.id,
                patient_name="Priya Nair (Baby)",
                category="IMMUNISATION",
                field_answers={
                    "Date of Birth": "2026-10-08",
                    "Birth Weight (kg)": 3.1,
                    "Mother Name": "Priya Nair",
                    "Delivery Place": "PHC Ramanagara",
                    "Medical History": "BCG and OPV-0 given at birth",
                    "House / Ward": "House 29, Ward 2",
                },
            ),
            PatientRecordCreate(
                id="BEN-007",
                register_id=anc_reg.id,
                patient_name="Rekha Patel",
                category="ANC",
                field_answers={
                    "Age": 26,
                    "Parity": "G2P1",
                    "LMP": "2026-06-12",
                    "Blood Group": "AB+",
                    "Medical History": "Normal previous delivery",
                    "Phone": "9845011107",
                    "House / Ward": "House 11, Ward 3",
                },
            ),
            PatientRecordCreate(
                id="BEN-008",
                register_id=nb_reg.id,
                patient_name="Savitha Bai (Baby)",
                category="IMMUNISATION",
                field_answers={
                    "Date of Birth": "2026-09-10",
                    "Birth Weight (kg)": 3.0,
                    "Mother Name": "Savitha Bai",
                    "Delivery Place": "CHC Channapatna",
                    "Medical History": "Pentavalent-1 completed",
                    "House / Ward": "House 05, Ward 2",
                },
            ),
        ]

        for patient in seed_patients:
            await self.create_patient(patient)

        seed_follow_ups = [
            FollowUpCreate(
                beneficiary_id="BEN-001",
                beneficiary_name="Sunita Devi",
                due_date="2026-11-21",
                purpose="ANC 3rd Trimester Checkup & TT2 Injection",
                vaccines_due=["TT2"],
                status="PENDING",
            ),
            FollowUpCreate(
                beneficiary_id="BEN-002",
                beneficiary_name="Lakshmi Gowda (Baby)",
                due_date="2026-11-21",
                purpose="HBNC Day 7 Newborn Weight & Feeding Check",
                vaccines_due=["BCG", "OPV-0"],
                status="PENDING",
            ),
            FollowUpCreate(
                beneficiary_id="BEN-004",
                beneficiary_name="Kavitha Reddy (Baby)",
                due_date="2026-11-21",
                purpose="HBNC Day 14 Low Birth Weight Follow-Up",
                vaccines_due=[],
                status="PENDING",
            ),
            FollowUpCreate(
                beneficiary_id="BEN-006",
                beneficiary_name="Priya Nair (Baby)",
                due_date="2026-11-23",
                purpose="6-Week Routine Immunisation Visit",
                vaccines_due=["Pentavalent-1", "OPV-1", "Rotavirus-1"],
                status="PENDING",
            ),
        ]

        for fup in seed_follow_ups:
            await self.create_follow_up(fup)

        return True

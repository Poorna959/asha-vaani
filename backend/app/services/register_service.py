"""Dynamic Custom Register Builder, Patient Intake, Search, and Excel/CSV Export Engine."""

from __future__ import annotations

import csv
import io
import re
from typing import Any, Optional

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

from app.db import Database
from app.models import (
    CustomRegister,
    CustomRegisterCreate,
    FieldAnswerItem,
    FieldType,
    PatientProfileView,
    PatientRecord,
    PatientRecordCreate,
    PatientRecordUpdate,
    RegisterField,
    RegisterFieldCreate,
)


class RegisterNotFoundError(LookupError):
    """Raised when a custom register ID does not exist."""


class PatientNotFoundError(LookupError):
    """Raised when a patient record ID does not exist."""


class DuplicateFieldError(ValueError):
    """Raised when adding a duplicate field name to a register."""


def _slugify_filename(name: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "_", name.strip().lower()).strip("_")
    return slug or "register_export"


def _sanitize_sheet_title(name: str) -> str:
    cleaned = re.sub(r"[\[\]:*?/\\]", " ", name).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    return (cleaned or "Register")[:31]


_NAME_PUNCT_RE = re.compile(r"[^a-z0-9\u0900-\u0d7f\s]+", re.IGNORECASE)
_PHONETIC_DIGRAPH_SUBS: tuple[tuple[str, str], ...] = (
    ("chh", "ch"),
    ("th", "t"),
    ("dh", "d"),
    ("bh", "b"),
    ("ph", "p"),
    ("kh", "k"),
    ("gh", "g"),
    ("sh", "s"),
    ("ee", "i"),
    ("ii", "i"),
    ("oo", "u"),
    ("uu", "u"),
    ("aa", "a"),
)
_DOUBLE_LETTER_RE = re.compile(r"([a-z])\1+")


def normalize_patient_name_key(name: str) -> str:
    """Normalize Indian patient names for phonetic duplicate matching (e.g. 'Savitha Rao' == 'Savita Rao')."""
    cleaned = _NAME_PUNCT_RE.sub(" ", name.strip().lower())
    tokens = [tok for tok in cleaned.split() if tok]
    norm_tokens: list[str] = []
    for tok in tokens:
        t = tok
        for src, dst in _PHONETIC_DIGRAPH_SUBS:
            t = t.replace(src, dst)
        t = _DOUBLE_LETTER_RE.sub(r"\1", t)
        if t.endswith("y") and len(t) > 2:
            t = t[:-1] + "i"
        norm_tokens.append(t)
    return " ".join(norm_tokens)


def _infer_field_type(value: Any) -> FieldType:
    if isinstance(value, bool):
        return "BOOLEAN"
    if isinstance(value, (int, float)):
        return "NUMBER"
    if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value.strip()):
        return "DATE"
    return "TEXT"


def _format_cell_value(value: Any) -> Any:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Yes" if value else "No"
    if isinstance(value, list):
        return ", ".join(str(item) for item in value)
    if isinstance(value, dict):
        return ", ".join(f"{k}: {v}" for k, v in value.items())
    return value


class RegisterService:
    """Business logic for custom registers, flexible patient profiles, search, and exports."""

    def __init__(self, db: Database) -> None:
        self.db = db

    # ------------------------------------------------------------------
    # Register & Dynamic Field Management
    # ------------------------------------------------------------------

    async def create_register(self, payload: CustomRegisterCreate) -> CustomRegister:
        """Create a custom register, deduplicating field names case-insensitively."""
        seen: set[str] = set()
        deduped_fields: list[RegisterFieldCreate] = []
        for idx, field in enumerate(payload.fields):
            key = field.field_name.strip().lower()
            if not key or key in seen:
                continue
            seen.add(key)
            deduped_fields.append(
                RegisterFieldCreate(
                    field_name=field.field_name.strip(),
                    field_type=field.field_type,
                    order_index=idx,
                )
            )

        cleaned = CustomRegisterCreate(
            name=payload.name.strip(),
            description=payload.description.strip(),
            fields=deduped_fields,
        )
        return await self.db.create_register(cleaned)

    async def list_registers(self) -> list[CustomRegister]:
        """List all custom registers with their defined fields."""
        return await self.db.list_registers()

    async def get_register(self, register_id: str) -> CustomRegister:
        """Fetch a custom register or raise RegisterNotFoundError."""
        register = await self.db.get_register(register_id)
        if register is None:
            raise RegisterNotFoundError(f"Register '{register_id}' not found.")
        return register

    async def add_field(
        self,
        register_id: str,
        field_in: RegisterFieldCreate,
    ) -> RegisterField:
        """Add a new custom field to a register, rejecting case-insensitive duplicates."""
        register = await self.get_register(register_id)
        target_key = field_in.field_name.strip().lower()
        for existing in register.fields:
            if existing.field_name.strip().lower() == target_key:
                raise DuplicateFieldError(
                    f"Field '{field_in.field_name.strip()}' already exists in register '{register.name}'."
                )
        return await self.db.add_register_field(register_id, field_in)

    # ------------------------------------------------------------------
    # Patient Intake, Update, Search & Mobile Field-Answer Profile View
    # ------------------------------------------------------------------

    async def _normalize_and_sync_answers(
        self,
        register: CustomRegister,
        answers: dict[str, Any],
        auto_add_fields: bool,
    ) -> dict[str, Any]:
        """Align answer keys with canonical register field names and optionally register new fields."""
        canonical_map = {f.field_name.strip().lower(): f.field_name for f in register.fields}
        normalized: dict[str, Any] = {}

        for raw_key, value in answers.items():
            cleaned_key = raw_key.strip()
            if not cleaned_key:
                continue
            lower_key = cleaned_key.lower()
            if lower_key in canonical_map:
                normalized[canonical_map[lower_key]] = value
            else:
                normalized[cleaned_key] = value
                if auto_add_fields:
                    await self.db.add_register_field(
                        register.id,
                        RegisterFieldCreate(
                            field_name=cleaned_key,
                            field_type=_infer_field_type(value),
                        ),
                    )
                    canonical_map[lower_key] = cleaned_key
        return normalized

    async def find_existing_patient_in_register(
        self,
        register_id: str,
        patient_name: str,
    ) -> Optional[PatientRecord]:
        """Find an existing patient in the same register using phonetic name normalization."""
        target_key = normalize_patient_name_key(patient_name)
        if not target_key:
            return None
        patients = await self.db.list_patients(register_id=register_id)
        for existing in patients:
            if normalize_patient_name_key(existing.patient_name) == target_key:
                return existing
        return None

    async def add_patient(
        self,
        payload: PatientRecordCreate,
        auto_add_fields: bool = True,
    ) -> PatientRecord:
        """Add a patient to a custom register, smartly upserting if the same patient already exists."""
        register = await self.get_register(payload.register_id)
        normalized_answers = await self._normalize_and_sync_answers(
            register=register,
            answers=payload.field_answers,
            auto_add_fields=auto_add_fields,
        )

        existing = await self.find_existing_patient_in_register(register.id, payload.patient_name)
        if existing is not None and (payload.id is None or payload.id == existing.id):
            non_empty_updates = {
                k: v for k, v in normalized_answers.items() if v is not None and v != ""
            }
            updated = await self.db.update_patient(
                existing.id,
                PatientRecordUpdate(
                    category=payload.category,
                    field_answers=non_empty_updates,
                ),
            )
            if updated is not None:
                return updated.model_copy(update={"matched_existing": True})

        cleaned_payload = PatientRecordCreate(
            id=payload.id,
            register_id=register.id,
            patient_name=payload.patient_name.strip(),
            category=payload.category,
            field_answers=normalized_answers,
        )
        return await self.db.create_patient(cleaned_payload)

    async def update_patient(
        self,
        patient_id: str,
        payload: PatientRecordUpdate,
        auto_add_fields: bool = True,
    ) -> PatientRecord:
        """Update an existing patient's metadata or merge dynamic field answers."""
        existing = await self.db.get_patient(patient_id)
        if existing is None:
            raise PatientNotFoundError(f"Patient '{patient_id}' not found.")

        normalized_answers: Optional[dict[str, Any]] = None
        if payload.field_answers is not None:
            register = await self.get_register(existing.register_id)
            normalized_answers = await self._normalize_and_sync_answers(
                register=register,
                answers=payload.field_answers,
                auto_add_fields=auto_add_fields,
            )

        updated = await self.db.update_patient(
            patient_id,
            PatientRecordUpdate(
                patient_name=payload.patient_name,
                category=payload.category,
                field_answers=normalized_answers,
            ),
        )
        if updated is None:
            raise PatientNotFoundError(f"Patient '{patient_id}' not found.")
        return updated

    async def search_patients(
        self,
        register_id: str,
        query: str = "",
    ) -> list[PatientRecord]:
        """Search patients by name (or ID) inside a specific custom register."""
        await self.get_register(register_id)
        return await self.db.search_patients_by_name(query=query, register_id=register_id)

    async def get_patient_profile_view(self, patient_id: str) -> PatientProfileView:
        """Return clean mobile-friendly Field: Answer list when ASHA taps a patient."""
        patient = await self.db.get_patient(patient_id)
        if patient is None:
            raise PatientNotFoundError(f"Patient '{patient_id}' not found.")

        register = await self.get_register(patient.register_id)
        items: list[FieldAnswerItem] = []
        included_keys: set[str] = set()

        for field in register.fields:
            answer = patient.field_answers.get(field.field_name)
            items.append(FieldAnswerItem(field_name=field.field_name, answer=answer))
            included_keys.add(field.field_name)

        for extra_key in sorted(patient.field_answers.keys()):
            if extra_key not in included_keys:
                items.append(
                    FieldAnswerItem(
                        field_name=extra_key,
                        answer=patient.field_answers[extra_key],
                    )
                )

        return PatientProfileView(
            patient_id=patient.id,
            register_id=register.id,
            register_name=register.name,
            patient_name=patient.patient_name,
            category=patient.category,
            items=items,
            created_at=patient.created_at,
            updated_at=patient.updated_at,
        )

    async def build_followup_context(self, patient_id: str) -> dict[str, Any]:
        """Build baseline context payload for FR-7.6 follow-up session continuity."""
        profile = await self.get_patient_profile_view(patient_id)
        recent_visits = await self.db.list_visits(beneficiary_id=patient_id)
        last_visit = recent_visits[0] if recent_visits else None

        return {
            "beneficiary_id": profile.patient_id,
            "beneficiary_name": profile.patient_name,
            "category": profile.category,
            "register_name": profile.register_name,
            "baseline_fields": {
                item.field_name: item.answer
                for item in profile.items
                if item.answer is not None and item.answer != ""
            },
            "last_visit": (
                {
                    "visit_type": last_visit.visit_type,
                    "weight_kg": last_visit.weight_kg,
                    "vaccinations_due": last_visit.vaccinations_due,
                    "next_visit_date": last_visit.next_visit_date,
                    "has_danger_sign": last_visit.has_danger_sign,
                    "created_at": last_visit.created_at,
                }
                if last_visit
                else None
            ),
        }

    # ------------------------------------------------------------------
    # Excel (.xlsx) & CSV Export Engine (FR-7.5)
    # ------------------------------------------------------------------

    def _build_export_table(
        self,
        register: CustomRegister,
        patients: list[PatientRecord],
    ) -> tuple[list[str], list[list[Any]]]:
        """Build ordered headers and rows for CSV/Excel export."""
        dynamic_columns: list[str] = [f.field_name for f in register.fields]
        known = set(dynamic_columns)

        for patient in patients:
            for key in sorted(patient.field_answers.keys()):
                if key not in known:
                    dynamic_columns.append(key)
                    known.add(key)

        headers = [
            "Patient ID",
            "Patient Name",
            "Category",
            *dynamic_columns,
            "Created At",
            "Updated At",
        ]

        rows: list[list[Any]] = []
        for patient in patients:
            row = [
                patient.id,
                patient.patient_name,
                patient.category,
                *[
                    _format_cell_value(patient.field_answers.get(col))
                    for col in dynamic_columns
                ],
                patient.created_at,
                patient.updated_at,
            ]
            rows.append(row)

        return headers, rows

    async def export_register_csv(self, register_id: str) -> tuple[str, bytes]:
        """Export a custom register as UTF-8 (with BOM for Excel Indic support) CSV bytes."""
        register = await self.get_register(register_id)
        patients = await self.db.list_patients(register_id=register_id)
        headers, rows = self._build_export_table(register, patients)

        buf = io.StringIO()
        writer = csv.writer(buf)
        writer.writerow(headers)
        writer.writerows(rows)

        filename = f"{_slugify_filename(register.name)}.csv"
        return filename, buf.getvalue().encode("utf-8-sig")

    async def export_register_xlsx(self, register_id: str) -> tuple[str, bytes]:
        """Export a custom register as a formatted Excel (.xlsx) workbook."""
        register = await self.get_register(register_id)
        patients = await self.db.list_patients(register_id=register_id)
        headers, rows = self._build_export_table(register, patients)

        wb = Workbook()
        ws = wb.active
        assert ws is not None
        ws.title = _sanitize_sheet_title(register.name)

        header_fill = PatternFill(start_color="0F766E", end_color="0F766E", fill_type="solid")
        header_font = Font(bold=True, color="FFFFFF", size=11)
        thin_side = Side(style="thin", color="CBD5E1")
        cell_border = Border(left=thin_side, right=thin_side, top=thin_side, bottom=thin_side)

        ws.append(headers)
        for col_idx in range(1, len(headers) + 1):
            cell = ws.cell(row=1, column=col_idx)
            cell.fill = header_fill
            cell.font = header_font
            cell.alignment = Alignment(horizontal="left", vertical="center")
            cell.border = cell_border

        for row in rows:
            ws.append(row)
            row_idx = ws.max_row
            for col_idx in range(1, len(headers) + 1):
                cell = ws.cell(row=row_idx, column=col_idx)
                cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=True)
                cell.border = cell_border

        ws.freeze_panes = "A2"

        # Auto-size columns within readable bounds
        for col_idx, header in enumerate(headers, start=1):
            max_len = len(str(header))
            for row in rows:
                val_len = len(str(row[col_idx - 1]))
                if val_len > max_len:
                    max_len = val_len
            ws.column_dimensions[get_column_letter(col_idx)].width = min(max(max_len + 4, 14), 42)

        out = io.BytesIO()
        wb.save(out)
        filename = f"{_slugify_filename(register.name)}.xlsx"
        return filename, out.getvalue()

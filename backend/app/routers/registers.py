"""REST API endpoints for Dynamic Custom Registers, Patient Profiles, Search, and Excel/CSV Export."""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from app.models import (
    CustomRegister,
    CustomRegisterCreate,
    PatientProfileView,
    PatientRecord,
    PatientRecordCreate,
    PatientRecordUpdate,
    RegisterField,
    RegisterFieldCreate,
)
from app.services.register_service import (
    DuplicateFieldError,
    PatientNotFoundError,
    RegisterNotFoundError,
    RegisterService,
)

router = APIRouter(prefix="/api", tags=["registers"])


def get_register_service(request: Request) -> RegisterService:
    """Resolve RegisterService from FastAPI app state."""
    service = getattr(request.app.state, "register_service", None)
    if service is None:
        raise RuntimeError("RegisterService not initialized on app.state.")
    return service


@router.post("/registers", response_model=CustomRegister, status_code=status.HTTP_201_CREATED)
async def create_register(
    payload: CustomRegisterCreate,
    service: RegisterService = Depends(get_register_service),
) -> CustomRegister:
    return await service.create_register(payload)


@router.get("/registers", response_model=list[CustomRegister])
async def list_registers(
    service: RegisterService = Depends(get_register_service),
) -> list[CustomRegister]:
    return await service.list_registers()


@router.get("/registers/{register_id}", response_model=CustomRegister)
async def get_register(
    register_id: str,
    service: RegisterService = Depends(get_register_service),
) -> CustomRegister:
    try:
        return await service.get_register(register_id)
    except RegisterNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.post(
    "/registers/{register_id}/fields",
    response_model=RegisterField,
    status_code=status.HTTP_201_CREATED,
)
async def add_register_field(
    register_id: str,
    field_in: RegisterFieldCreate,
    service: RegisterService = Depends(get_register_service),
) -> RegisterField:
    try:
        return await service.add_field(register_id, field_in)
    except RegisterNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    except DuplicateFieldError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.post(
    "/registers/{register_id}/patients",
    response_model=PatientRecord,
    status_code=status.HTTP_201_CREATED,
)
async def add_patient_to_register(
    register_id: str,
    payload: PatientRecordCreate,
    service: RegisterService = Depends(get_register_service),
) -> PatientRecord:
    try:
        scoped = payload.model_copy(update={"register_id": register_id})
        return await service.add_patient(scoped)
    except RegisterNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/registers/{register_id}/patients", response_model=list[PatientRecord])
async def search_register_patients(
    register_id: str,
    q: str = Query(default="", description="Filter patients in this register by name or ID"),
    service: RegisterService = Depends(get_register_service),
) -> list[PatientRecord]:
    try:
        return await service.search_patients(register_id=register_id, query=q)
    except RegisterNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/patients/{patient_id}/profile", response_model=PatientProfileView)
async def get_patient_profile(
    patient_id: str,
    service: RegisterService = Depends(get_register_service),
) -> PatientProfileView:
    try:
        return await service.get_patient_profile_view(patient_id)
    except PatientNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.patch("/patients/{patient_id}", response_model=PatientRecord)
async def update_patient_record(
    patient_id: str,
    payload: PatientRecordUpdate,
    service: RegisterService = Depends(get_register_service),
) -> PatientRecord:
    try:
        return await service.update_patient(patient_id, payload)
    except PatientNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/patients/{patient_id}/followup-context")
async def get_patient_followup_context(
    patient_id: str,
    service: RegisterService = Depends(get_register_service),
) -> dict[str, Any]:
    try:
        return await service.build_followup_context(patient_id)
    except PatientNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/registers/{register_id}/export")
async def export_register(
    register_id: str,
    format: Literal["xlsx", "csv"] = Query(default="xlsx"),
    service: RegisterService = Depends(get_register_service),
) -> Response:
    try:
        if format == "csv":
            filename, data = await service.export_register_csv(register_id)
            media_type = "text/csv; charset=utf-8"
        else:
            filename, data = await service.export_register_xlsx(register_id)
            media_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    except RegisterNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    return Response(
        content=data,
        media_type=media_type,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )

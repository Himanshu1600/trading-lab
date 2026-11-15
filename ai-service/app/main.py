from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(title="Trading Lab AI service")
app.include_router(router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}

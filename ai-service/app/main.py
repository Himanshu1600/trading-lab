from fastapi import FastAPI

app = FastAPI(title="Trading Lab AI service")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}

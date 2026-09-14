"""Run the local agent API with Uvicorn."""

import uvicorn

from jusika_agent.config import Settings


def main() -> None:
    settings = Settings()
    uvicorn.run(
        "jusika_agent.app:app",
        host="127.0.0.1",
        port=settings.port,
        reload=False,
    )


if __name__ == "__main__":
    main()

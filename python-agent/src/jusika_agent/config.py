"""Application configuration loaded from environment variables."""

from enum import StrEnum

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class CommandInterpreterProvider(StrEnum):
    """Supported natural-language command interpreters."""

    RULES = "rules"
    OPENAI = "openai"


class CheckpointProvider(StrEnum):
    """Supported LangGraph checkpoint stores."""

    MEMORY = "memory"
    POSTGRES = "postgres"


class Settings(BaseSettings):
    """Runtime settings for the agent service."""

    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        env_prefix="JUSIKA_AGENT_",
        extra="ignore",
    )

    environment: str = "local"
    spring_backend_url: str = "http://localhost:8080"
    port: int = Field(default=8000, ge=1, le=65535)
    command_interpreter: CommandInterpreterProvider = CommandInterpreterProvider.RULES
    openai_model: str = "gpt-4o-mini"
    checkpoint_provider: CheckpointProvider = CheckpointProvider.MEMORY
    checkpoint_database_url: SecretStr = SecretStr("")

    openai_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias="OPENAI_API_KEY",
    )
    spring_read_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias="JUSIKA_INTERNAL_READ_API_KEY",
    )
    spring_order_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias="JUSIKA_INTERNAL_ORDER_API_KEY",
    )

    spring_connect_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    spring_read_timeout_seconds: float = Field(default=8.0, gt=0, le=60)

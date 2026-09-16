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
    log_level: str = "INFO"
    readiness_timeout_seconds: float = Field(default=3.0, gt=0, le=15)
    spring_backend_url: str = "http://localhost:8080"
    port: int = Field(default=8000, ge=1, le=65535)
    command_interpreter: CommandInterpreterProvider = CommandInterpreterProvider.RULES
    openai_model: str = "gpt-4o-mini"
    openai_timeout_seconds: float = Field(default=15.0, gt=0, le=60)
    openai_max_output_tokens: int = Field(default=1000, ge=256, le=4096)
    openai_max_attempts: int = Field(default=3, ge=1, le=5)
    openai_retry_base_delay_seconds: float = Field(default=0.25, ge=0, le=5)
    checkpoint_provider: CheckpointProvider = CheckpointProvider.MEMORY
    checkpoint_database_url: SecretStr = SecretStr("")
    checkpoint_session_lock_timeout_seconds: float = Field(default=30.0, gt=0, le=120)

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
    spring_read_max_attempts: int = Field(default=3, ge=1, le=5)
    spring_retry_base_delay_seconds: float = Field(default=0.25, ge=0, le=5)

"""Application configuration loaded from environment variables."""

import json
import re
from enum import StrEnum

from pydantic import Field, SecretStr, model_validator
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
        hide_input_in_errors=True,
    )

    environment: str = "local"
    mobile_auth_required: bool = False
    mobile_credentials: SecretStr = SecretStr("{}")
    mobile_requests_per_minute: int = Field(default=30, ge=1, le=300)
    mobile_ip_requests_per_minute: int = Field(default=60, ge=1, le=600)
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

    def mobile_users(self) -> dict[str, str]:
        try:
            users = json.loads(self.mobile_credentials.get_secret_value())
            if not isinstance(users, dict) or len(users) > 100:
                raise ValueError
            for user, token in users.items():
                if (
                    not isinstance(user, str)
                    or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", user)
                    or not isinstance(token, str)
                    or not re.fullmatch(r"[a-zA-Z0-9_-]{32,256}", token)
                ):
                    raise ValueError
            if len(set(users.values())) != len(users):
                raise ValueError
        except (ValueError, TypeError):
            raise ValueError("모바일 인증 설정 형식이 올바르지 않습니다.") from None
        return dict(users)

    @model_validator(mode="after")
    def validate_mobile_auth(self) -> "Settings":
        users = self.mobile_users()
        if self.environment != "local" and not self.mobile_auth_required:
            raise ValueError("로컬 외 환경에서는 모바일 인증이 필수입니다.")
        if self.mobile_auth_required and not users:
            raise ValueError("모바일 인증에는 최소 한 명의 사용자 토큰이 필요합니다.")
        return self

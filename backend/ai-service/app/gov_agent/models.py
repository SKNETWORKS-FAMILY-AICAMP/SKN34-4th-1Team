from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class GovAgentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    message: str = Field(min_length=1, max_length=500)
    has_selected_program: bool = Field(alias="hasSelectedProgram")
    search_query: str | None = Field(default=None, max_length=500, alias="searchQuery")
    pending_search_question: str | None = Field(default=None, max_length=160, alias="pendingSearchQuestion")


class GovAgentDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: Literal["SEARCH", "EVIDENCE", "UNSUPPORTED"]

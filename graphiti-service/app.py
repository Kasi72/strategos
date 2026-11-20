"""
Strategos memory service: a thin HTTP wrapper over graphiti-core (0.31.x) for the Kotlin server's GraphitiMemoryAdapter.

- LLM (entity and fact extraction): DeepSeek through Graphiti's OpenAI-compatible client.
- Embeddings: Voyage AI (voyage-4-lite, 1024 dims) by default; EMBEDDING_PROVIDER=ollama for a local model instead.
- Graph: FalkorDB.

Episodes are queued and processed one at a time (Graphiti resolves entities against what is already in the graph, so
order matters within a group). The Kotlin side never waits for ingestion; reads hit what is already processed.
"""
import asyncio
import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI
from openai import AsyncOpenAI
from pydantic import BaseModel

os.environ.setdefault("GRAPHITI_TELEMETRY_ENABLED", "false")

from graphiti_core import Graphiti  # noqa: E402
from graphiti_core.cross_encoder.openai_reranker_client import OpenAIRerankerClient  # noqa: E402
from graphiti_core.driver.falkordb_driver import FalkorDriver  # noqa: E402
from graphiti_core.embedder.openai import OpenAIEmbedder, OpenAIEmbedderConfig  # noqa: E402
from graphiti_core.llm_client.config import LLMConfig  # noqa: E402
from graphiti_core.llm_client.openai_generic_client import OpenAIGenericClient  # noqa: E402
from graphiti_core.nodes import EpisodeType  # noqa: E402

log = logging.getLogger("strategos-memory")
logging.basicConfig(level=logging.INFO)


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None:
        raise RuntimeError(f"{name} is not set")
    return value


def build_embedder():
    """Voyage AI by default (hosted, ~$0.02 per 1M tokens on the lite models). EMBEDDING_PROVIDER=ollama uses a local model.

    The vector size is fixed when the graph is first built: changing the model later means re-embedding the memory.
    """
    provider = env("EMBEDDING_PROVIDER", "voyage").lower()
    if provider == "voyage":
        from graphiti_core.embedder.voyage import VoyageAIEmbedder, VoyageAIEmbedderConfig
        return VoyageAIEmbedder(config=VoyageAIEmbedderConfig(
            api_key=env("VOYAGE_API_KEY"),
            embedding_model=env("EMBEDDING_MODEL", "voyage-4-lite"),
            embedding_dim=int(env("EMBEDDING_DIM", "1024")),
        ))
    # Any OpenAI-compatible embeddings endpoint: a local Ollama (default here) or OpenAI itself.
    return OpenAIEmbedder(config=OpenAIEmbedderConfig(
        api_key=env("EMBEDDING_API_KEY", "ollama"),
        base_url=env("EMBEDDING_BASE_URL", "http://ollama:11434/v1"),
        embedding_model=env("EMBEDDING_MODEL", "nomic-embed-text"),
        embedding_dim=int(env("EMBEDDING_DIM", "768")),
    ))


def build_graphiti() -> Graphiti:
    llm_config = LLMConfig(
        api_key=env("LLM_API_KEY"),
        base_url=env("LLM_BASE_URL", "https://api.deepseek.com"),
        model=env("LLM_MODEL", "deepseek-chat"),
        small_model=env("LLM_SMALL_MODEL", env("LLM_MODEL", "deepseek-chat")),
    )
    embedder = build_embedder()
    # The reranker is only used by cross-encoder search recipes, which this service does not call; it is given the
    # DeepSeek client so Graphiti never falls back to its default OpenAI one.
    reranker = OpenAIRerankerClient(config=llm_config, client=AsyncOpenAI(api_key=llm_config.api_key, base_url=llm_config.base_url))
    driver = FalkorDriver(host=env("FALKORDB_HOST", "falkordb"), port=int(env("FALKORDB_PORT", "6379")),
                          database=env("FALKORDB_DATABASE", "strategos"))
    # DeepSeek has no json_schema response format: json_object puts the schema in the prompt instead.
    return Graphiti(graph_driver=driver, llm_client=OpenAIGenericClient(config=llm_config, structured_output_mode=env("LLM_STRUCTURED_OUTPUT", "json_object")), embedder=embedder,
                    cross_encoder=reranker)


queue: asyncio.Queue = asyncio.Queue()
state = {"graphiti": None, "processed": 0, "failed": 0}


async def worker():
    while True:
        ep = await queue.get()
        try:
            await state["graphiti"].add_episode(
                name=ep.name, episode_body=ep.episode_body, source_description=ep.source_description,
                reference_time=ep.when(), source=EpisodeType.text, group_id=ep.group_id,
            )
            state["processed"] += 1
        except Exception as e:  # one bad episode never stops the queue; its text is never logged
            state["failed"] += 1
            log.warning("episode %s failed: %s", ep.name, type(e).__name__)
        finally:
            queue.task_done()


@asynccontextmanager
async def lifespan(_: FastAPI):
    g = build_graphiti()
    await g.build_indices_and_constraints()
    state["graphiti"] = g
    task = asyncio.create_task(worker())
    yield
    task.cancel()
    await g.close()


app = FastAPI(title="strategos-memory", lifespan=lifespan)


class EpisodeIn(BaseModel):
    group_id: str
    name: str
    episode_body: str
    source_description: str = "strategos"
    reference_time: str | None = None

    def when(self) -> datetime:
        if not self.reference_time:
            return datetime.now(timezone.utc)
        return datetime.fromisoformat(self.reference_time.replace("Z", "+00:00"))


class SearchIn(BaseModel):
    group_ids: list[str]
    query: str
    num_results: int = 10


def iso(d: datetime | None) -> str | None:
    return d.astimezone(timezone.utc).isoformat().replace("+00:00", "Z") if d else None


@app.get("/health")
async def health():
    return {"ok": state["graphiti"] is not None, "queued": queue.qsize(), "processed": state["processed"], "failed": state["failed"]}


@app.post("/episodes", status_code=202)
async def add_episode(ep: EpisodeIn):
    await queue.put(ep)
    return {"queued": queue.qsize()}


@app.post("/drain")
async def drain():
    """Waits until every queued episode is processed (for integration tests)."""
    await queue.join()
    return {"processed": state["processed"], "failed": state["failed"]}


@app.post("/search")
async def search(q: SearchIn):
    edges = await state["graphiti"].search(q.query, group_ids=q.group_ids, num_results=q.num_results)
    return {"facts": [{
        "uuid": e.uuid, "group_id": e.group_id, "name": e.name, "fact": e.fact,
        "valid_at": iso(e.valid_at), "invalid_at": iso(e.invalid_at), "expired_at": iso(e.expired_at),
        "created_at": iso(e.created_at), "episodes": e.episodes,
    } for e in edges]}

import os

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from tortoise.contrib.fastapi import register_tortoise
from tortoise.expressions import Q

from models import Operadora, Estatistica

load_dotenv()
DATABASE_URL = os.getenv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/postgres")

app = FastAPI(title="ANS API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/operadoras")
async def listar_operadoras(
    page: int = Query(1, ge=1),
    size: int = Query(10, ge=1, le=200),
    nome: str | None = None,
    uf: str | None = None,
):
    query = Q()
    if nome:
        query &= Q(razao_social__icontains=nome) | Q(nome_fantasia__icontains=nome)
    if uf:
        query &= Q(uf=uf)

    total = await Operadora.filter(query).count()
    items = await Operadora.filter(query).offset((page - 1) * size).limit(size).values(
        "cnpj",
        "registro_ans",
        "razao_social",
        "nome_fantasia",
        "modalidade",
        "uf",
    )
    return {"page": page, "size": size, "total": total, "items": items}


@app.get("/estatisticas/{cnpj}")
async def obter_estatisticas(cnpj: str):
    dados = await Estatistica.filter(cnpj=cnpj).values(
        "cnpj",
        "registro_ans",
        "modalidade",
        "uf",
        "total",
        "media",
        "desvio_padrao",
    )
    if not dados:
        raise HTTPException(status_code=404, detail="Operadora não encontrada")
    return {"cnpj": cnpj, "estatisticas": dados}


register_tortoise(
    app,
    db_url=DATABASE_URL,
    modules={"models": ["models"]},
    generate_schemas=False,
    add_exception_handlers=True,
)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8085)

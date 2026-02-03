from tortoise import fields
from tortoise.models import Model


class Operadora(Model):
    cnpj = fields.CharField(pk=True, max_length=20)
    registro_ans = fields.CharField(max_length=20, null=True)
    razao_social = fields.TextField(null=True)
    nome_fantasia = fields.TextField(null=True)
    modalidade = fields.TextField(null=True)
    logradouro = fields.TextField(null=True)
    numero = fields.TextField(null=True)
    complemento = fields.TextField(null=True)
    bairro = fields.TextField(null=True)
    cidade = fields.TextField(null=True)
    uf = fields.CharField(max_length=2, null=True)
    cep = fields.TextField(null=True)
    ddd = fields.TextField(null=True)
    telefone = fields.TextField(null=True)
    fax = fields.TextField(null=True)
    endereco_eletronico = fields.TextField(null=True)
    representante = fields.TextField(null=True)
    cargo_representante = fields.TextField(null=True)
    data_registro_ans = fields.DateField(null=True)

    class Meta:
        table = "operadoras_cadastrais"
        schema = "ans"


class Estatistica(Model):
    id = fields.IntField(pk=True)
    cnpj = fields.CharField(max_length=20)
    registro_ans = fields.CharField(max_length=20, null=True)
    modalidade = fields.TextField(null=True)
    uf = fields.CharField(max_length=2, null=True)
    total = fields.DecimalField(max_digits=18, decimal_places=2)
    media = fields.DecimalField(max_digits=18, decimal_places=2)
    desvio_padrao = fields.DecimalField(max_digits=18, decimal_places=2)

    class Meta:
        table = "despesas_agregadas_final"
        schema = "ans"

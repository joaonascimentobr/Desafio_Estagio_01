package br.com.seuorg.ans;

import java.math.BigDecimal;

public class DespesaEvento {

    private String registroAns;
    private String cnpj;
    private String razaoSocial;
    private String trimestre;
    private String trimestreReferencia;
    private Integer ano;
    private BigDecimal valorDespesas;
    private String statusValidacao;

    public String getRegistroAns() {
        return registroAns;
    }

    public void setRegistroAns(String registroAns) {
        this.registroAns = registroAns;
    }

    public String getCnpj() {
        return cnpj;
    }

    public void setCnpj(String cnpj) {
        this.cnpj = cnpj;
    }

    public String getRazaoSocial() {
        return razaoSocial;
    }

    public void setRazaoSocial(String razaoSocial) {
        this.razaoSocial = razaoSocial;
    }

    public String getTrimestre() {
        return trimestre;
    }

    public void setTrimestre(String trimestre) {
        this.trimestre = trimestre;
    }

    public String getTrimestreReferencia() {
        return trimestreReferencia;
    }

    public void setTrimestreReferencia(String trimestreReferencia) {
        this.trimestreReferencia = trimestreReferencia;
    }

    public Integer getAno() {
        return ano;
    }

    public void setAno(Integer ano) {
        this.ano = ano;
    }

    public BigDecimal getValorDespesas() {
        return valorDespesas;
    }

    public void setValorDespesas(BigDecimal valorDespesas) {
        this.valorDespesas = valorDespesas;
    }

    public String getStatusValidacao() {
        return statusValidacao;
    }

    public void setStatusValidacao(String statusValidacao) {
        this.statusValidacao = statusValidacao;
    }
}

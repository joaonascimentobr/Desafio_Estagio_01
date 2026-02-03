package br.com.seuorg.ans;

import java.math.BigDecimal;

public class DespesaEvento {

    private String cnpj;
    private String registroAns;
    private String trimestre;
    private Integer ano;
    private BigDecimal valorDespesas;

    public String getRegistroAns() {
        return registroAns;
    }

    public void setCnpj(String cnpj) {
        this.cnpj = cnpj;
    }

    public String getRegistroAns() {
        return registroAns;
    }

    public void setRegistroAns(String registroAns) {
        this.registroAns = registroAns;
    }

    public String getTrimestre() {
        return trimestre;
    }

    public void setTrimestre(String trimestre) {
        this.trimestre = trimestre;
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
}

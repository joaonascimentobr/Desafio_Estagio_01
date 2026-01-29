package br.com.seuorg.ans;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Trimestres {

    @JsonProperty("DATA")
    private LocalDate data;

    @JsonProperty("REG_ANS")
    private Integer regAns;

    @JsonProperty("CD_CONTA_CONTABIL")
    private Integer cdContaContabil;

    @JsonProperty("DESCRICAO")
    private String descricao;

    @JsonProperty("VL_SALDO_INICIAL")
    private BigDecimal vlSaldoInicial;

    @JsonProperty("VL_SALDO_FINAL")
    private BigDecimal vlSaldoFinal;

    public Trimestres() {}

    public LocalDate getData() {
        return data;
    }

    public void setData(LocalDate data) {
        this.data = data;
    }

    public Integer getRegAns() {
        return regAns;
    }

    public void setRegAns(Integer regAns) {
        this.regAns = regAns;
    }

    public Integer getCdContaContabil() {
        return cdContaContabil;
    }

    public void setCdContaContabil(Integer cdContaContabil) {
        this.cdContaContabil = cdContaContabil;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public BigDecimal getVlSaldoInicial() {
        return vlSaldoInicial;
    }

    public void setVlSaldoInicial(BigDecimal vlSaldoInicial) {
        this.vlSaldoInicial = vlSaldoInicial;
    }

    public BigDecimal getVlSaldoFinal() {
        return vlSaldoFinal;
    }

    public void setVlSaldoFinal(BigDecimal vlSaldoFinal) {
        this.vlSaldoFinal = vlSaldoFinal;
    }
}

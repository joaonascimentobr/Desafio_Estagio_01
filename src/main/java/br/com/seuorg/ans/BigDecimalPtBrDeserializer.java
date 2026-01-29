package br.com.seuorg.ans;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

public class BigDecimalPtBrDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String valor = p.getText();

        if (valor == null || valor.isBlank()) {
            return BigDecimal.ZERO;
        }

        // Remove separador de milhar e troca vírgula por ponto
        valor = valor.replace(".", "").replace(",", ".");

        return new BigDecimal(valor);
    }
}

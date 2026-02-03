package br.com.seuorg.ans.service;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ValidadorService {

    private static final Logger LOGGER = Logger.getLogger(ValidadorService.class.getName());

    public boolean isCnpjValido(String cnpj) {
        if (cnpj == null || !cnpj.matches("\\d{14}")) {
            return false;
        }
        if (cnpj.chars().distinct().count() == 1) {
            return false;
        }
        int[] pesos1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] pesos2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

        int soma1 = 0;
        for (int i = 0; i < 12; i++) {
            soma1 += (cnpj.charAt(i) - '0') * pesos1[i];
        }
        int dig1 = soma1 % 11;
        dig1 = (dig1 < 2) ? 0 : 11 - dig1;

        int soma2 = 0;
        for (int i = 0; i < 13; i++) {
            soma2 += (cnpj.charAt(i) - '0') * pesos2[i];
        }
        int dig2 = soma2 % 11;
        dig2 = (dig2 < 2) ? 0 : 11 - dig2;

        return cnpj.charAt(12) == (char) ('0' + dig1)
                && cnpj.charAt(13) == (char) ('0' + dig2);
    }

    public boolean isRazaoSocialValida(String razaoSocial) {
        return razaoSocial != null && !razaoSocial.isBlank();
    }

    public boolean isValorPositivo(BigDecimal valor) {
        return valor != null && valor.signum() > 0;
    }

    public boolean validarOuLogar(String cnpj, String razaoSocial, BigDecimal valor) {
        if (!isCnpjValido(cnpj)) {
            LOGGER.log(Level.WARNING, "Registro descartado por CNPJ invalido: {0}", cnpj);
            return false;
        }
        if (!isValorPositivo(valor)) {
            LOGGER.log(Level.WARNING, "Registro descartado por valor nao positivo. CNPJ={0}", cnpj);
            return false;
        }
        if (razaoSocial != null && razaoSocial.isBlank()) {
            LOGGER.log(Level.WARNING, "Registro descartado por razao social vazia. CNPJ={0}", cnpj);
            return false;
        }
        return true;
    }
}

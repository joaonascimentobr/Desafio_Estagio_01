package br.com.seuorg.ans;

public class OperadoraCadastro {

    private final String cnpj;
    private final String registroAns;
    private final String modalidade;
    private final String uf;
    private final String razaoSocial;

    public OperadoraCadastro(String cnpj, String registroAns, String modalidade, String uf, String razaoSocial) {
        this.cnpj = cnpj;
        this.registroAns = registroAns;
        this.modalidade = modalidade;
        this.uf = uf;
        this.razaoSocial = razaoSocial;
    }

    public String getCnpj() {
        return cnpj;
    }

    public String getRegistroAns() {
        return registroAns;
    }

    public String getModalidade() {
        return modalidade;
    }

    public String getUf() {
        return uf;
    }

    public String getRazaoSocial() {
        return razaoSocial;
    }
}

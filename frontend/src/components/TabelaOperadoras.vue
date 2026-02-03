<template>
  <div>
    <input v-model="search" placeholder="Buscar operadora" />
    <table>
      <thead>
        <tr>
          <th>CNPJ</th>
          <th>Razão Social</th>
          <th>Nome Fantasia</th>
          <th>Modalidade</th>
          <th>UF</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.cnpj">
          <td>{{ item.cnpj }}</td>
          <td>{{ item.razao_social }}</td>
          <td>{{ item.nome_fantasia }}</td>
          <td>{{ item.modalidade }}</td>
          <td>{{ item.uf }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script>
export default {
  name: "TabelaOperadoras",
  data() {
    return {
      search: "",
      items: [],
      page: 1,
      size: 10,
      debounceId: null,
    };
  },
  watch: {
    search() {
      clearTimeout(this.debounceId);
      this.debounceId = setTimeout(() => {
        this.page = 1;
        this.fetchOperadoras();
      }, 300);
    },
  },
  mounted() {
    this.fetchOperadoras();
  },
  methods: {
    async fetchOperadoras() {
      const params = new URLSearchParams();
      params.set("page", this.page);
      params.set("size", this.size);
      if (this.search) {
        params.set("nome", this.search);
      }
      const response = await fetch(`http://localhost:8085/operadoras?${params.toString()}`);
      if (!response.ok) {
        return;
      }
      const data = await response.json();
      this.items = data.items || [];
    },
  },
};
</script>

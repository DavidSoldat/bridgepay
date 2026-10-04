package com.bridgepay.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SalesCsvTest {

    @Test
    void plainValuesAreWrittenAsIs_nullAsEmpty() {
        assertThat(SalesCsv.cell("APPROVED")).isEqualTo("APPROVED");
        assertThat(SalesCsv.cell("100.00")).isEqualTo("100.00");
        assertThat(SalesCsv.cell(null)).isEmpty();
    }

    @Test
    void separatorsQuotesAndNewlinesAreQuoted() {
        assertThat(SalesCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(SalesCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SalesCsv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void formulaLookalikesAreDefused() {
        assertThat(SalesCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(SalesCsv.cell("+cmd")).isEqualTo("'+cmd");
        assertThat(SalesCsv.cell("-2")).isEqualTo("'-2");
        assertThat(SalesCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SalesCsv.cell("=a,b")).isEqualTo("\"'=a,b\"");
    }
}

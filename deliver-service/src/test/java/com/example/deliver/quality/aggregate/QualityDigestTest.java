package com.example.deliver.quality.aggregate;

import java.util.List;

import com.example.deliver.quality.aggregate.QualityDigest.CaseStamp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-4/AC-7: one digest implementation only. Order of the incoming stamps must not matter, but
 * any change to a case version or a result must change the hash.
 */
class QualityDigestTest {

    private final QualityDigest digest = new QualityDigest(null);

    @Test
    void sha256MatchesTheStandardVector() {
        assertThat(QualityDigest.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void stampOrderIsIrrelevant() {
        List<CaseStamp> a = List.of(new CaseStamp(2, 1, "PASS"), new CaseStamp(1, 3, "FAIL"));
        List<CaseStamp> b = List.of(new CaseStamp(1, 3, "FAIL"), new CaseStamp(2, 1, "PASS"));
        assertThat(digest.digest(a)).isEqualTo(digest.digest(b));
    }

    @Test
    void digestChangesWithAResultOrAVersion() {
        String base = digest.digest(List.of(new CaseStamp(1, 1, "PASS")));
        assertThat(digest.digest(List.of(new CaseStamp(1, 1, "FAIL")))).isNotEqualTo(base);
        assertThat(digest.digest(List.of(new CaseStamp(1, 2, "PASS")))).isNotEqualTo(base);
        assertThat(digest.digest(List.of(new CaseStamp(1, 1, "PASS"),
                new CaseStamp(2, 1, "PASS")))).isNotEqualTo(base);
    }

    @Test
    void emptyExecutionHasAStableDigest() {
        assertThat(digest.digest(List.of()))
                .isEqualTo(digest.digest((List<CaseStamp>) null));
    }
}

package com.nova.link.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SocialRelation} — the directional per-player relation
 * data class introduced by §11.6 item-18 / PANEL proposal 08.
 *
 * <p>Focuses on the composite natural-key equality contract (sourceId,
 * targetId, type) and the convenience-constructor timestamp stamping.
 */
@DisplayName("SocialRelation")
class SocialRelationTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Nested
    @DisplayName("convenience constructor")
    class ConvenienceConstructor {

        @Test
        @DisplayName("stamps createdAt and updatedAt to the same instant")
        void stampsBothTimestampsToNow() {
            long before = System.currentTimeMillis();
            SocialRelation relation = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE);
            long after = System.currentTimeMillis();

            assertThat(relation.getCreatedAt()).isEqualTo(relation.getUpdatedAt());
            assertThat(relation.getCreatedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("exposes all constructor arguments via getters")
        void exposesFields() {
            long timestamp = 1_700_000_000_000L;
            SocialRelation relation = new SocialRelation(
                    ALICE, BOB, SocialRelation.RelationType.FAVORITE, timestamp, timestamp + 1);

            assertThat(relation.getSourceId()).isEqualTo(ALICE);
            assertThat(relation.getTargetId()).isEqualTo(BOB);
            assertThat(relation.getType()).isEqualTo(SocialRelation.RelationType.FAVORITE);
            assertThat(relation.getCreatedAt()).isEqualTo(timestamp);
            assertThat(relation.getUpdatedAt()).isEqualTo(timestamp + 1);
        }
    }

    @Nested
    @DisplayName("equality is keyed on the composite natural key")
    class CompositeKeyEquality {

        @Test
        @DisplayName("two relations with the same source/target/type are equal regardless of timestamps")
        void equalWhenKeyMatches() {
            SocialRelation a = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 2L);
            SocialRelation b = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 9_999L, 9_999L);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different target breaks equality even with identical timestamps")
        void unequalWhenTargetDiffers() {
            SocialRelation a = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);
            SocialRelation b = new SocialRelation(ALICE, CAROL, SocialRelation.RelationType.IGNORE, 1L, 1L);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different type breaks equality for the same source/target pair")
        void unequalWhenTypeDiffers() {
            SocialRelation ignore = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);
            SocialRelation favorite = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.FAVORITE, 1L, 1L);

            assertThat(ignore).isNotEqualTo(favorite);
        }

        @Test
        @DisplayName("ignore is directional: A→B is not equal to B→A")
        void directional() {
            SocialRelation aToB = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);
            SocialRelation bToA = new SocialRelation(BOB, ALICE, SocialRelation.RelationType.IGNORE, 1L, 1L);

            assertThat(aToB).isNotEqualTo(bToA);
        }

        @Test
        @DisplayName("a relation equals itself and not null/foreign types")
        void reflexivityAndNullForeign() {
            SocialRelation relation = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 1L);

            assertThat(relation).isEqualTo(relation);
            assertThat(relation).isNotEqualTo(null);
            assertThat(relation).isNotEqualTo("not a relation");
        }
    }

    @Test
    @DisplayName("toString mentions source, target and type")
    void toStringMentionsKey() {
        SocialRelation relation = new SocialRelation(ALICE, BOB, SocialRelation.RelationType.IGNORE, 1L, 2L);

        String string = relation.toString();
        assertThat(string).contains(ALICE.toString());
        assertThat(string).contains(BOB.toString());
        assertThat(string).contains("IGNORE");
        assertThat(string).contains("createdAt=1");
        assertThat(string).contains("updatedAt=2");
    }

    @Test
    @DisplayName("RelationType has exactly IGNORE and FAVORITE")
    void relationTypeEnum() {
        assertThat(SocialRelation.RelationType.values())
                .containsExactly(SocialRelation.RelationType.IGNORE, SocialRelation.RelationType.FAVORITE);
    }
}

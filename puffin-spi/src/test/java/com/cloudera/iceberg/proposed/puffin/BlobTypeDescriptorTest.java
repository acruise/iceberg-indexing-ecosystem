package com.cloudera.iceberg.proposed.puffin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.Compaction;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.Deletes;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.Inference;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.MergePrecision;
import com.cloudera.iceberg.proposed.puffin.BlobTypeDescriptor.Scope;
import org.junit.jupiter.api.Test;

class BlobTypeDescriptorTest {
  private static BlobTypeDescriptor.Builder base(Scope scope) {
    return BlobTypeDescriptor.builder("com.example.test-v1")
        .title("test")
        .definition("https://example.com/test-v1")
        .scope(scope)
        .inputFields(1)
        .deletes(Deletes.MASKED);
  }

  @Test
  void exclusionIsTheDefaultAndIsLeftOutOfTheJson() {
    BlobTypeDescriptor d = base(Scope.DATA_FILE).compaction(Compaction.REBUILD).build();
    assertThat(d.inference()).isEqualTo(Inference.EXCLUSION);
    assertThat(d.toJson().has("inference")).isFalse();
  }

  @Test
  void aLocatingTypeSaysSoInItsJson() {
    BlobTypeDescriptor d = base(Scope.DATA_FILE).compaction(Compaction.REBUILD).inference(Inference.LOCATION).build();
    assertThat(d.toJson().get("inference").asText()).isEqualTo("location");
    assertThat(d.toJson().get("deletes").asText()).isEqualTo("masked");
  }

  @Test
  void locatingAndAggregatingTypesMergeExactlyOrNotAtAll() {
    for (Inference i : new Inference[] {Inference.LOCATION, Inference.AGGREGATE}) {
      assertThatThrownBy(() -> base(Scope.DATA_FILE).merge(MergePrecision.CONSERVATIVE).inference(i).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot merge conservatively");
      assertThat(base(Scope.DATA_FILE).merge(MergePrecision.EXACT).inference(i).build().mergePrecision())
          .contains(MergePrecision.EXACT);
    }
  }

  @Test
  void inferenceIsDefinedOnlyForDataFileScopedBlobs() {
    assertThatThrownBy(() -> base(Scope.SNAPSHOT).inference(Inference.AGGREGATE).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("data-file-scoped");
  }
}

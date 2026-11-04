package app.serene.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which file of an npu-model artifact the app imports on a phone ([isNpuModelFor]). */
class NpuModelFileTest {
    @Test fun takesTheBuildForThePhonesChip() {
        assertTrue(isNpuModelFor("Qwen3-0.6B.sm8550.litertlm", "SM8550"))
        assertTrue(isNpuModelFor("Qwen3-0.6B.sm8750.litertlm", "SM8750"))
        assertTrue(isNpuModelFor("npu-model-18/Qwen3-0.6B.SM8550.litertlm", "sm8550"))
        assertTrue(isNpuModelFor("Qwen3-0.6B.sm8750-2.litertlm", "SM8750")) // a released file name
    }

    @Test fun skipsBuildsForOtherChips() {
        assertFalse(isNpuModelFor("Qwen3-0.6B.sm8750.litertlm", "SM8550"))
        assertFalse(isNpuModelFor("Qwen3-0.6B.sm8550.litertlm", "SM8750"))
        assertFalse(isNpuModelFor("Qwen3-0.6B.sm8550.litertlm", ""))
    }

    @Test fun takesAModelNamedForNoChip() {
        assertTrue(isNpuModelFor("model.litertlm", "SM8550"))
        assertTrue(isNpuModelFor("Qwen3-0.6B-helmi.litertlm", "SM8650"))
    }

    @Test fun skipsOtherFiles() {
        assertFalse(isNpuModelFor("SHA256SUMS", "SM8550"))
        assertFalse(isNpuModelFor("Qwen3-0.6B.sm8550.litertlm.part", "SM8550"))
    }
}

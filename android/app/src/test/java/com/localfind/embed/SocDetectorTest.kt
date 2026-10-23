package com.localfind.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SocDetector] chipset parsing and NPU model file resolution.
 *
 * Verifies dynamic matching against all available models in the official Hugging Face repository:
 * https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main
 */
class SocDetectorTest {

    @Test
    fun testSnapdragon8Gen3_S24Ultra() {
        // Properties observed on Samsung Galaxy S24 Ultra (SM-S928B)
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "QTI",
            rawModel = "SM8650",
            rawHardware = "qcom",
            rawBoard = "pineapple"
        )
        assertEquals("Qualcomm", soc.vendor)
        assertEquals("Snapdragon 8 Gen 3", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_Qualcomm_SM8650.litertlm", soc.expectedNpuModelName)
        assertEquals("V75", soc.htpArchitecture)
    }

    @Test
    fun testSnapdragon8Gen2_S23() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Qualcomm",
            rawModel = "SM8550",
            rawHardware = "qcom",
            rawBoard = "kalama"
        )
        assertEquals("Qualcomm", soc.vendor)
        assertEquals("Snapdragon 8 Gen 2", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_Qualcomm_SM8550.litertlm", soc.expectedNpuModelName)
        assertEquals("V73", soc.htpArchitecture)
    }

    @Test
    fun testSnapdragon8Elite_S25() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Qualcomm",
            rawModel = "SM8750",
            rawHardware = "qcom",
            rawBoard = "sun"
        )
        assertEquals("Qualcomm", soc.vendor)
        assertEquals("Snapdragon 8 Elite", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_Qualcomm_SM8750.litertlm", soc.expectedNpuModelName)
        assertEquals("V79", soc.htpArchitecture)
    }

    @Test
    fun testSnapdragon8EliteGen5_SM8850() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Qualcomm",
            rawModel = "SM8850",
            rawHardware = "qcom",
            rawBoard = "canoe"
        )
        assertEquals("Qualcomm", soc.vendor)
        assertEquals("Snapdragon 8 Elite Gen 5", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_Qualcomm_SM8850.litertlm", soc.expectedNpuModelName)
        assertEquals("V81", soc.htpArchitecture)
    }

    @Test
    fun testUnsupportedSnapdragon_hasNullNpuModel() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Qualcomm",
            rawModel = "SM7150",
            rawHardware = "qcom",
            rawBoard = "sm7150"
        )
        assertEquals("Qualcomm", soc.vendor)
        // Unlisted Snapdragon: the raw chip model is surfaced through the marketing name
        assertEquals("Snapdragon (SM7150)", soc.marketingName)
        assertNull(soc.expectedNpuModelName)
        assertNull(soc.htpArchitecture)
    }

    @Test
    fun testMediaTekDimensity9400() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "MediaTek",
            rawModel = "MT6991",
            rawHardware = "mt6991",
            rawBoard = "k6991v1"
        )
        assertEquals("MediaTek", soc.vendor)
        assertEquals("Dimensity 9400", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_MediaTek_MT6991.litertlm", soc.expectedNpuModelName)
    }

    @Test
    fun testGoogleTensorG5() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Google",
            rawModel = "Tensor G5",
            rawHardware = "tensor",
            rawBoard = "laguna"
        )
        assertEquals("Google", soc.vendor)
        assertEquals("Google Tensor G5", soc.marketingName)
        assertEquals("embeddinggemma-2-740m_Google_Tensor_G5.litertlm", soc.expectedNpuModelName)
    }

    @Test
    fun testSunfishBoard_isNotSnapdragon8Elite() {
        // Pixel 4a: board "sunfish" must not match the Snapdragon 8 Elite board "sun"
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "Google",
            rawModel = "",
            rawHardware = "sunfish",
            rawBoard = "sunfish"
        )
        assertEquals("Google", soc.vendor)
        assertNull(soc.expectedNpuModelName)
        assertNull(soc.htpArchitecture)
    }

    @Test
    fun testFirstKnown_skipsBlankAndUnknownValues() {
        assertEquals("SM8650", SocDetector.firstKnown("", "unknown", "UNKNOWN", "SM8650", "qcom"))
        assertEquals("", SocDetector.firstKnown("", "unknown"))
    }

    @Test
    fun testGenericFallback() {
        val soc = SocDetector.parseSocInfo(
            rawManufacturer = "UnknownVendor",
            rawModel = "XYZ123",
            rawHardware = "generic",
            rawBoard = "generic"
        )
        assertEquals("UnknownVendor", soc.vendor)
        assertNull(soc.expectedNpuModelName)
    }
}

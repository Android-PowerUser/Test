package com.google.ai.sample

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

// Since ScreenOperatorAccessibilityService is an Android Service, we might need Robolectric
// if we were testing more of its lifecycle or Android-specific features.
// For testing a private method like convertCoordinate, it might be simpler,
// but if it accesses resources (like DisplayMetrics indirectly), Robolectric can be helpful.
// For now, let's assume we can mock essential parts if direct invocation is too complex.
// However, convertCoordinate itself doesn't use Android APIs directly, only its parameters.
// The service's executeCommand DOES use Android APIs (resources.displayMetrics).

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK]) // Configure for a specific SDK if necessary
class ScreenOperatorAccessibilityServiceTest {

    // We are testing a private method. We'll need an instance of the service
    // or use reflection with a null instance if the method is static-like (which it is not).
    // Let's instantiate it simply. Robolectric can help with service instantiation.
    private lateinit var service: ScreenOperatorAccessibilityService
    private lateinit var convertCoordinateMethod: Method

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockResources: Resources

    @Mock
    private lateinit var mockDisplayMetrics: DisplayMetrics

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this) // Initialize mocks

        // Mock Android framework components if needed by the method under test
        // For convertCoordinate, it does not directly use Android context/resources.
        // However, if we were testing executeCommand, we would need more extensive mocking.
        `when`(mockContext.resources).thenReturn(mockResources)
        `when`(mockResources.displayMetrics).thenReturn(mockDisplayMetrics)

        service = ScreenOperatorAccessibilityService()
        // If ScreenOperatorAccessibilityService had dependencies injected via constructor,
        // we would need to provide them here. For now, it has a default constructor.

        // Use reflection to make the private method accessible
        convertCoordinateMethod = ScreenOperatorAccessibilityService::class.java.getDeclaredMethod(
            "convertCoordinate", // Method name
            String::class.java,   // First parameter type (String)
            Int::class.java       // Second parameter type (Int)
        ).apply {
            isAccessible = true // Make it accessible
        }
    }

    private fun invokeConvertCoordinate(coordinateString: String, screenSize: Int): Float {
        // The method is not static, so it needs an instance of the class
        return convertCoordinateMethod.invoke(service, coordinateString, screenSize) as Float
    }

    @Test
    fun `convertCoordinate - percentage values`() {
        assertEquals(500.0f, invokeConvertCoordinate("50%", 1000))
        assertEquals(255.0f, invokeConvertCoordinate("25.5%", 1000))
        assertEquals(0.0f, invokeConvertCoordinate("0%", 1000))
        assertEquals(1000.0f, invokeConvertCoordinate("100%", 1000))
        assertEquals(100.0f, invokeConvertCoordinate("10%", 1000)) // Test with whole number percentage
        assertEquals(333.0f, invokeConvertCoordinate("33.3%", 1000))
    }

    @Test
    fun `convertCoordinate - pixel values`() {
        assertEquals(123.0f, invokeConvertCoordinate("123", 1000))
        assertEquals(123.45f, invokeConvertCoordinate("123.45", 1000))
        assertEquals(0.0f, invokeConvertCoordinate("0", 1000))
        assertEquals(1000.0f, invokeConvertCoordinate("1000", 1000))
    }

    @Test
    fun `convertCoordinate - edge cases and error handling`() {
        // Invalid percentage (non-numeric)
        assertEquals(0.0f, invokeConvertCoordinate("abc%", 1000))
        // Invalid pixel (non-numeric)
        assertEquals(0.0f, invokeConvertCoordinate("abc", 1000))
        // Invalid format (mix of valid and invalid)
        assertEquals(0.0f, invokeConvertCoordinate("50%abc", 1000))
        // Empty string
        assertEquals(0.0f, invokeConvertCoordinate("", 1000))
        // Percentage without number
        assertEquals(0.0f, invokeConvertCoordinate("%", 1000))
        // Just a number with percent somewhere else
        assertEquals(0.0f, invokeConvertCoordinate("50%20", 1000))
        // Negative percentage
        assertEquals(-100.0f, invokeConvertCoordinate("-10%", 1000))
        // Negative pixel
        assertEquals(-100.0f, invokeConvertCoordinate("-100", 1000))
    }

    @Test
    fun `convertCoordinate - zero screen size`() {
        assertEquals(0.0f, invokeConvertCoordinate("50%", 0))
        assertEquals(123.0f, invokeConvertCoordinate("123", 0)) // Pixel value should be unaffected by screen size
        assertEquals(0.0f, invokeConvertCoordinate("0%", 0))
    }

    @Test
    fun `convertCoordinate - large values`() {
        assertEquals(20000.0f, invokeConvertCoordinate("200%", 10000))
        assertEquals(5000.0f, invokeConvertCoordinate("5000", 10000))
    }
}

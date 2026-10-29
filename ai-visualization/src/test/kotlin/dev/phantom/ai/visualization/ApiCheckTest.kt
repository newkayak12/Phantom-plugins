package dev.phantom.ai.visualization

import dev.phantom.ai.visualization.actions.ApiCheck
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCheckTest {
  @Test fun apiOfTheBuiltAgainstAiIntegrationIsFound() = assertTrue(ApiCheck.available)
}

package com.openminis.app.ui.onboarding

import androidx.compose.runtime.Composable
import com.openminis.app.data.repository.ProviderRepository

/**
 * "Select models" reached from the session list after onboarding. Same page as the onboarding's
 * model step (one implementation), where finishing or skipping simply goes back.
 */
@Composable
fun OnboardingModelSelectionScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    ModelSelectionStep(
        providerRepository = providerRepository,
        onBack = onBack,
        onComplete = onBack,
    )
}

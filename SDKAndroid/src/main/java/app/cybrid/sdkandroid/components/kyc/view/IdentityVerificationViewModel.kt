package app.cybrid.sdkandroid.components.kyc.view

import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cybrid.sdkandroid.AppModule
import app.cybrid.cybrid_api_bank.client.apis.CustomersApi
import app.cybrid.cybrid_api_bank.client.apis.IdentityVerificationsApi
import app.cybrid.cybrid_api_bank.client.infrastructure.ApiClient
import app.cybrid.cybrid_api_bank.client.models.*
import app.cybrid.sdkandroid.Cybrid
import app.cybrid.sdkandroid.components.KYCView
import app.cybrid.sdkandroid.util.Logger
import app.cybrid.sdkandroid.util.LoggerEvents
import app.cybrid.sdkandroid.util.Polling
import java.math.BigDecimal as JavaBigDecimal
import app.cybrid.sdkandroid.util.getResult
import kotlinx.coroutines.*

class IdentityVerificationViewModel: ViewModel() {

    private var customerService = AppModule.getClient().createService(CustomersApi::class.java)
    private var identityService = AppModule.getClient().createService(IdentityVerificationsApi::class.java)

    var customerJob: Polling? = null
    var identityJob: Polling? = null

    var customerGuid = Cybrid.instance.customerGuid
    var UIState: MutableState<KYCView.KYCViewState>? = null

    var latestIdentityVerification: IdentityVerificationWrapper? = null

    fun setDataProvider(dataProvider: ApiClient)  {

        customerService = dataProvider.createService(CustomersApi::class.java)
        identityService = dataProvider.createService(IdentityVerificationsApi::class.java)
    }

    suspend fun createCustomerTest() {

        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {
                viewModelScope.let { scope ->
                    val waitFor = scope.async {

                        val customerResult = getResult {
                            customerService.createCustomer(
                                postCustomerBankModel = PostCustomerBankModel(
                                    type = PostCustomerBankModel.Type.individual)
                            )
                        }
                        Logger.log(LoggerEvents.DATA_FETCHED, "Create - Customer")
                        customerGuid = customerResult.data?.guid ?: customerGuid
                        getCustomerStatus()
                    }
                    waitFor.await()
                }
            }
        }
    }

    fun getCustomerStatus() {

        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {
                viewModelScope.let { scope ->
                    scope.launch {

                        val customerResult = getResult {
                            customerService.getCustomer(
                                customerGuid = customerGuid)
                        }
                        Logger.log(LoggerEvents.DATA_FETCHED, "Fetch - Customer Status")
                        checkCustomerStatus(customerResult.data?.state ?: CustomerBankModel.State.storing)
                    }
                }
            }
        }
    }

    fun getIdentityVerificationStatus(identityWrapper: IdentityVerificationWrapper? = null) {

        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {
                viewModelScope.let { scope ->
                    scope.launch {

                        var lastVerification = identityWrapper?.identityVerification ?: getLastIdentityVerification()
                        if (lastVerification == null ||
                            lastVerification.state == IdentityVerificationBankModel.State.expired) {
                            lastVerification = createIdentityVerification()
                        }

                        val lastVerificationWithDetails = fetchIdentityVerificationWithDetailsStatus(guid = lastVerification?.guid!!)
                        val returnedWrapper = IdentityVerificationWrapper(identity = lastVerification, details = lastVerificationWithDetails)
                        checkIdentityRecordStatus(returnedWrapper)
                    }
                }
            }
        }
    }

    suspend fun fetchIdentityVerificationWithDetailsStatus(guid: String): IdentityVerificationWithDetailsBankModel? {

        var identityVerificationDetails: IdentityVerificationWithDetailsBankModel? = null
        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {
                viewModelScope.let { scope ->
                    val waitFor = scope.async {
                        val recordResponse = getResult {
                            identityService.getIdentityVerification(
                                identityVerificationGuid = guid
                            )
                        }
                        Logger.log(LoggerEvents.DATA_FETCHED, "Fetch - Identity Verification Status")
                        identityVerificationDetails = recordResponse.data
                        return@async identityVerificationDetails
                    }
                    waitFor.await()
                }
            }
        }
        return identityVerificationDetails
    }

    suspend fun getLastIdentityVerification(): IdentityVerificationBankModel? {

        var verification: IdentityVerificationBankModel? = null
        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {
                viewModelScope.let { scope ->
                    val waitFor = scope.async {

                        val identityResponse = getResult {
                            identityService.listIdentityVerifications(
                                customerGuid = customerGuid,
                                page = JavaBigDecimal(0),
                                perPage = JavaBigDecimal(1)
                            )
                        }
                        Logger.log(LoggerEvents.DATA_FETCHED, "Fetch - Identity Verifications List")
                        val total: JavaBigDecimal = identityResponse.data?.total ?: JavaBigDecimal(0)
                        if (total > JavaBigDecimal(0)) {

                            val verifications = identityResponse.data?.objects
                            verifications?.sortedBy { it.createdAt }
                            verification = verifications?.get(0)
                        }
                        return@async verification
                    }
                    waitFor.await()
                }
            }
        }
        return verification
    }

    suspend fun createIdentityVerification(): IdentityVerificationBankModel? {

        var verification: IdentityVerificationBankModel? = null
        Cybrid.instance.let { cybrid ->
            if (!cybrid.invalidToken) {

                viewModelScope.let {
                    val waitFor = it.async {

                        val recordResponse = getResult {
                            identityService.createIdentityVerification(
                                postIdentityVerificationBankModel = PostIdentityVerificationBankModel(
                                    type = PostIdentityVerificationBankModel.Type.kyc,
                                    method = PostIdentityVerificationBankModel.Method.idAndSelfie,
                                    customerGuid = customerGuid,
                                )
                            )
                        }
                        Logger.log(LoggerEvents.DATA_FETCHED, "Create - Identity Verification")
                        verification = recordResponse.data
                        return@async verification
                    }
                    waitFor.await()
                }
            }
        }
        return verification
    }

    fun checkCustomerStatus(state: CustomerBankModel.State) {

        when (state) {

            CustomerBankModel.State.storing -> {

                if (customerJob == null) {
                    customerJob = Polling { getCustomerStatus() }
                }
            }

            CustomerBankModel.State.verified -> {

                customerJob?.stop()
                customerJob = null
                UIState?.value = KYCView.KYCViewState.VERIFIED
            }

            CustomerBankModel.State.unverified -> {

                customerJob?.stop()
                customerJob = null
                getIdentityVerificationStatus()
            }

            CustomerBankModel.State.rejected -> {

                customerJob?.stop()
                customerJob = null
                UIState?.value = KYCView.KYCViewState.ERROR
            }
        }
    }

    fun checkIdentityRecordStatus(identityWrapper: IdentityVerificationWrapper?) {

        when(identityWrapper?.identityVerificationDetails?.state) {

            IdentityVerificationWithDetailsBankModel.State.storing -> {

                if (identityJob == null) {
                    identityJob = Polling { getIdentityVerificationStatus(identityWrapper = identityWrapper) }
                }
            }

            IdentityVerificationWithDetailsBankModel.State.waiting -> {

                if (identityWrapper.identityVerificationDetails?.personaState == IdentityVerificationWithDetailsBankModel.PersonaState.completed ||
                    identityWrapper.identityVerificationDetails?.personaState == IdentityVerificationWithDetailsBankModel.PersonaState.processing) {

                    if (identityJob == null) {
                        identityJob = Polling { getIdentityVerificationStatus(identityWrapper = identityWrapper) }
                    }

                } else {

                    identityJob?.stop()
                    identityJob = null
                    checkIdentityPersonaStatus(identityWrapper = identityWrapper)
                }
            }

            IdentityVerificationWithDetailsBankModel.State.expired -> {

                identityJob?.stop()
                identityJob = null
                getIdentityVerificationStatus(null)
            }

            IdentityVerificationWithDetailsBankModel.State.completed -> {

                identityJob?.stop()
                identityJob = null
                UIState?.value = KYCView.KYCViewState.VERIFIED
            }

            else -> {

                identityJob?.stop()
                identityJob = null
            }
        }
    }

    fun checkIdentityPersonaStatus(identityWrapper: IdentityVerificationWrapper?) {

        this.latestIdentityVerification = identityWrapper
        when(identityWrapper?.identityVerificationDetails?.personaState) {

            IdentityVerificationWithDetailsBankModel.PersonaState.waiting -> {

                UIState?.value = KYCView.KYCViewState.REQUIRED
            }

            IdentityVerificationWithDetailsBankModel.PersonaState.pending -> {

                UIState?.value = KYCView.KYCViewState.REQUIRED
            }

            IdentityVerificationWithDetailsBankModel.PersonaState.reviewing -> {

                UIState?.value = KYCView.KYCViewState.REVIEWING
            }

            IdentityVerificationWithDetailsBankModel.PersonaState.expired -> {
                getIdentityVerificationStatus(null)
            }

            else -> {

                UIState?.value = KYCView.KYCViewState.ERROR
            }
        }
    }

    class IdentityVerificationWrapper(identity: IdentityVerificationBankModel?, details: IdentityVerificationWithDetailsBankModel?) {

        var identityVerification: IdentityVerificationBankModel?
        var identityVerificationDetails: IdentityVerificationWithDetailsBankModel?

        init {

            this.identityVerification = identity
            this.identityVerificationDetails = details
        }
    }
}
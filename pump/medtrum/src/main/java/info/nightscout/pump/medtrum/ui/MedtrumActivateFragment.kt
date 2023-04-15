package info.nightscout.androidaps.plugins.pump.eopatch.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.pump.medtrum.R
import info.nightscout.pump.medtrum.code.PatchStep
import info.nightscout.pump.medtrum.databinding.FragmentMedtrumActivateBinding
import info.nightscout.pump.medtrum.ui.MedtrumBaseFragment
import info.nightscout.pump.medtrum.ui.viewmodel.MedtrumViewModel
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import javax.inject.Inject

class MedtrumActivateFragment : MedtrumBaseFragment<FragmentMedtrumActivateBinding>() {

    @Inject lateinit var aapsLogger: AAPSLogger

    companion object {

        fun newInstance(): MedtrumActivateFragment = MedtrumActivateFragment()
    }

    override fun getLayoutId(): Int = R.layout.fragment_medtrum_activate

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            viewModel = ViewModelProvider(requireActivity(), viewModelFactory).get(MedtrumViewModel::class.java)
            viewModel?.apply {
                setupStep.observe(viewLifecycleOwner) {
                    when (it) {
                        MedtrumViewModel.SetupStep.PRIMED -> Unit // Nothing to do here, previous state
                        MedtrumViewModel.SetupStep.ACTIVATED -> btnPositive.visibility = View.VISIBLE
                        MedtrumViewModel.SetupStep.ERROR   -> {
                            ToastUtils.errorToast(requireContext(), "Error Activating") // TODO: String resource and show error message
                            moveStep(PatchStep.CANCEL)
                        }

                        else                               -> {
                            ToastUtils.errorToast(requireContext(), "Unexpected state: $it") // TODO: String resource and show error message
                            moveStep(PatchStep.CANCEL)
                        }
                    }
                }
                startActivate()
            }
        }
    }
}
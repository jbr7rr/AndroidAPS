package info.nightscout.androidaps.plugins.pump.eopatch.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.pump.medtrum.R
import info.nightscout.pump.medtrum.code.PatchStep
import info.nightscout.pump.medtrum.databinding.FragmentMedtrumPreparePatchBinding
import info.nightscout.pump.medtrum.ui.MedtrumBaseFragment
import info.nightscout.pump.medtrum.ui.viewmodel.MedtrumViewModel
import info.nightscout.rx.logging.AAPSLogger
import info.nightscout.rx.logging.LTag
import javax.inject.Inject

class MedtrumPreparePatchFragment : MedtrumBaseFragment<FragmentMedtrumPreparePatchBinding>() {

    @Inject lateinit var aapsLogger: AAPSLogger

    companion object {

        fun newInstance(): MedtrumPreparePatchFragment = MedtrumPreparePatchFragment()
    }

    override fun getLayoutId(): Int = R.layout.fragment_medtrum_prepare_patch

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        aapsLogger.debug(LTag.PUMP, "MedtrumPreparePatchFragment onViewCreated")
        binding.apply {
            viewModel = ViewModelProvider(requireActivity(), viewModelFactory).get(MedtrumViewModel::class.java)
            viewModel?.apply {
                setupStep.observe(viewLifecycleOwner) {
                    when (it) {
                        MedtrumViewModel.SetupStep.INITIAL -> btnPositive.visibility = View.GONE
                        // TODO: Confirmation dialog
                        MedtrumViewModel.SetupStep.FILLED  -> btnPositive.visibility = View.VISIBLE

                        MedtrumViewModel.SetupStep.ERROR   -> {
                            ToastUtils.errorToast(requireContext(), "Error preparing patch") // TODO: String resource and show error message
                            moveStep(PatchStep.CANCEL)
                        }

                        else                               -> {
                            ToastUtils.errorToast(requireContext(), "Unexpected state: $it") // TODO: String resource and show error message
                            moveStep(PatchStep.CANCEL)
                        }
                    }
                }
                preparePatch()
            }
        }
    }
}
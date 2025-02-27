package org.thoughtcrime.securesms.mediasend.v2.text

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.drawToBitmap
import androidx.core.view.postDelayed
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.databinding.StoriesTextPostCreationFragmentBinding
import org.thoughtcrime.securesms.linkpreview.LinkPreviewRepository
import org.thoughtcrime.securesms.linkpreview.LinkPreviewViewModel
import org.thoughtcrime.securesms.mediasend.CameraDisplay
import org.thoughtcrime.securesms.mediasend.v2.HudCommand
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionViewModel
import org.thoughtcrime.securesms.mediasend.v2.stories.StoriesMultiselectForwardActivity
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendRepository
import org.thoughtcrime.securesms.mediasend.v2.text.send.TextStoryPostSendResult
import org.thoughtcrime.securesms.safety.SafetyNumberBottomSheet
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.LifecycleDisposable
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil
import org.thoughtcrime.securesms.util.visible

class TextStoryPostCreationFragment : Fragment(R.layout.stories_text_post_creation_fragment), TextStoryPostTextEntryFragment.Callback, SafetyNumberBottomSheet.Callbacks {

  private var _binding: StoriesTextPostCreationFragmentBinding? = null
  private val binding: StoriesTextPostCreationFragmentBinding get() = _binding!!

  private val sharedViewModel: MediaSelectionViewModel by viewModels(
    ownerProducer = {
      requireActivity()
    }
  )

  private val viewModel: TextStoryPostCreationViewModel by viewModels(
    ownerProducer = {
      requireActivity()
    },
    factoryProducer = {
      TextStoryPostCreationViewModel.Factory(TextStoryPostSendRepository())
    }
  )

  private val linkPreviewViewModel: LinkPreviewViewModel by viewModels(
    ownerProducer = {
      requireActivity()
    },
    factoryProducer = {
      LinkPreviewViewModel.Factory(LinkPreviewRepository())
    }
  )

  private val lifecycleDisposable = LifecycleDisposable()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    _binding = StoriesTextPostCreationFragmentBinding.bind(view)

    binding.storyTextPost.showCloseButton()

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable += sharedViewModel.hudCommands.subscribe {
      if (it == HudCommand.GoToCapture) {
        findNavController().popBackStack()
      }
    }

    viewModel.typeface.observe(viewLifecycleOwner) { typeface ->
      binding.storyTextPost.setTypeface(typeface)
    }

    viewModel.state.observe(viewLifecycleOwner) { state ->
      binding.backgroundSelector.background = state.backgroundColor.chatBubbleMask
      binding.storyTextPost.bindFromCreationState(state)

      if (state.linkPreviewUri != null) {
        linkPreviewViewModel.onTextChanged(requireContext(), state.linkPreviewUri, 0, state.linkPreviewUri.lastIndex)
      } else {
        linkPreviewViewModel.onSend()
      }

      val canSend = state.body.isNotEmpty() || !state.linkPreviewUri.isNullOrEmpty()
      binding.send.alpha = if (canSend) 1f else 0.5f
      binding.send.isEnabled = canSend
    }

    LiveDataUtil.combineLatest(viewModel.state, linkPreviewViewModel.linkPreviewState) { viewState, linkState ->
      Pair(viewState.body.isBlank(), linkState)
    }.observe(viewLifecycleOwner) { (useLargeThumb, linkState) ->
      binding.storyTextPost.bindLinkPreviewState(linkState, View.GONE, useLargeThumb)
      binding.storyTextPost.postAdjustLinkPreviewTranslationY()
    }

    binding.storyTextPost.setTextViewClickListener {
      binding.storyTextPost.hidePostContent()
      binding.storyTextPost.isEnabled = false
      TextStoryPostTextEntryFragment().show(childFragmentManager, null)
    }

    binding.backgroundProtection.setOnClickListener {
      viewModel.cycleBackgroundColor()
    }

    binding.addLinkProtection.setOnClickListener {
      TextStoryPostLinkEntryFragment().show(childFragmentManager, null)
    }

    binding.storyTextPost.setLinkPreviewCloseListener {
      viewModel.setLinkPreview("")
    }

    val launcher = registerForActivityResult(StoriesMultiselectForwardActivity.SelectionContract()) {
      if (it.isNotEmpty()) {
        performSend(it.toSet())
      } else {
        binding.send.isClickable = true
        binding.sendInProgressIndicator.visible = false
      }
    }

    binding.send.setOnClickListener {
      binding.send.isClickable = false
      binding.sendInProgressIndicator.visible = true

      binding.storyTextPost.hideCloseButton()

      val contacts = (sharedViewModel.destination.getRecipientSearchKeyList() + sharedViewModel.destination.getRecipientSearchKey())
        .filterIsInstance(ContactSearchKey::class.java)
        .toSet()

      if (contacts.isEmpty()) {
        val bitmap = binding.storyTextPost.drawToBitmap()
        viewModel.compressToBlob(bitmap).observeOn(AndroidSchedulers.mainThread()).subscribe { uri ->
          launcher.launch(
            StoriesMultiselectForwardActivity.Args(
              MultiselectForwardFragmentArgs(
                title = R.string.MediaReviewFragment__send_to,
                canSendToNonPush = false,
                storySendRequirements = Stories.MediaTransform.SendRequirements.VALID_DURATION,
                isSearchEnabled = false
              ),
              listOf(uri)
            )
          )
        }
      } else {
        performSend(contacts)
      }
    }

    initializeScenePositioning()
  }

  override fun onResume() {
    super.onResume()
    binding.storyTextPost.showCloseButton()
    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }

  override fun onTextStoryPostTextEntryDismissed() {
    binding.storyTextPost.postDelayed(resources.getInteger(R.integer.text_entry_exit_duration).toLong()) {
      binding.storyTextPost.showPostContent()
      binding.storyTextPost.isEnabled = true
    }
  }

  private fun initializeScenePositioning() {
    val cameraDisplay = CameraDisplay.getDisplay(requireActivity())

    if (!cameraDisplay.roundViewFinderCorners) {
      binding.storyTextPostCard.radius = 0f
    }

    binding.send.updateLayoutParams<ConstraintLayout.LayoutParams> {
      bottomMargin = cameraDisplay.getToggleBottomMargin()
    }

    listOf(binding.backgroundProtection, binding.addLinkProtection).forEach {
      it.updateLayoutParams<ConstraintLayout.LayoutParams> {
        bottomMargin += cameraDisplay.getCameraCaptureMarginBottom(resources)
      }
    }

    if (cameraDisplay.getCameraViewportGravity() == CameraDisplay.CameraViewportGravity.CENTER) {
      val constraintSet = ConstraintSet()
      constraintSet.clone(binding.scene)
      constraintSet.connect(R.id.story_text_post_card, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
      constraintSet.applyTo(binding.scene)
    } else {
      binding.storyTextPostCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
        bottomMargin = cameraDisplay.getCameraViewportMarginBottom()
      }
    }
  }

  private fun performSend(contacts: Set<ContactSearchKey>) {
    lifecycleDisposable += viewModel.send(
      contacts = contacts,
      linkPreviewViewModel.linkPreviewState.value?.linkPreview?.orElse(null)
    ).observeOn(AndroidSchedulers.mainThread()).subscribe { result ->
      when (result) {
        TextStoryPostSendResult.Success -> {
          Toast.makeText(requireContext(), R.string.TextStoryPostCreationFragment__sent_story, Toast.LENGTH_SHORT).show()
          requireActivity().finish()
        }
        TextStoryPostSendResult.Failure -> {
          Toast.makeText(requireContext(), R.string.TextStoryPostCreationFragment__failed_to_send_story, Toast.LENGTH_SHORT).show()
          requireActivity().finish()
        }
        is TextStoryPostSendResult.UntrustedRecordsError -> {
          binding.send.isClickable = true
          binding.sendInProgressIndicator.visible = false

          SafetyNumberBottomSheet
            .forIdentityRecordsAndDestinations(result.untrustedRecords, contacts.toList())
            .show(childFragmentManager)
        }
      }
    }
  }

  override fun sendAnywayAfterSafetyNumberChangedInBottomSheet(destinations: List<ContactSearchKey.RecipientSearchKey>) {
    performSend(destinations.toSet())
  }

  override fun onMessageResentAfterSafetyNumberChangeInBottomSheet() {
    error("Unsupported, we do not hand in a message id.")
  }

  override fun onCanceled() = Unit
}

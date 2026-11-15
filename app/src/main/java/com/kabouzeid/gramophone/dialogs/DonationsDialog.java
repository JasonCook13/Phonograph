package com.kabouzeid.gramophone.dialogs;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.afollestad.materialdialogs.MaterialDialog;

/**
 * Personal build: in-app donations (Google Play billing) removed.
 * Points to the original project instead.
 */
public class DonationsDialog extends DialogFragment {

    public static DonationsDialog create() {
        return new DonationsDialog();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new MaterialDialog.Builder(requireContext())
                .title("Support Phonograph")
                .content("This is a personal build of Phonograph by Karim Abou Zeid. "
                        + "To support the original developer, visit the project on GitHub.")
                .positiveText("Open GitHub")
                .negativeText(android.R.string.cancel)
                .onPositive((dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/kabouzeid/Phonograph"))))
                .build();
    }
}

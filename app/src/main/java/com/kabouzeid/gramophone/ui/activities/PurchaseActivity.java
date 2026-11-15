package com.kabouzeid.gramophone.ui.activities;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Personal build: Google Play billing has been removed and Pro is always unlocked,
 * so this screen just closes itself. Kept so existing references still compile.
 */
public class PurchaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}

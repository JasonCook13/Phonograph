package com.kabouzeid.gramophone.ui.activities.intro;

import android.os.Bundle;

import com.heinrichreimersoftware.materialintro.app.IntroActivity;
import com.heinrichreimersoftware.materialintro.slide.SimpleSlide;
import com.kabouzeid.gramophone.R;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AppIntroActivity extends IntroActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setButtonCtaVisible(true);
        setButtonNextVisible(false);
        setButtonBackVisible(false);

        setButtonCtaTintMode(BUTTON_CTA_TINT_MODE_TEXT);

        addSlide(new SimpleSlide.Builder()
                .title(R.string.app_name)
                .description(R.string.intro_welcome)
                .image(R.mipmap.ic_launcher)
                .background(R.color.brand_burgundy)
                .backgroundDark(R.color.brand_burgundy_dark)
                .build());
        addSlide(new SimpleSlide.Builder()
                .title(R.string.composers)
                .description(R.string.intro_composers)
                .background(R.color.brand_burgundy_dark)
                .backgroundDark(R.color.brand_burgundy_darker)
                .build());
        addSlide(new SimpleSlide.Builder()
                .title(R.string.albums)
                .description(R.string.intro_albums)
                .background(R.color.brand_burgundy)
                .backgroundDark(R.color.brand_burgundy_dark)
                .build());
        addSlide(new SimpleSlide.Builder()
                .title(R.string.intro_new_music_title)
                .description(R.string.intro_new_music)
                .background(R.color.brand_burgundy_dark)
                .backgroundDark(R.color.brand_burgundy_darker)
                .build());
        addSlide(new SimpleSlide.Builder()
                .title(R.string.label_playing_queue)
                .description(R.string.open_playing_queue_instruction)
                .image(R.drawable.tutorial_queue_swipe_up)
                .background(R.color.brand_burgundy)
                .backgroundDark(R.color.brand_burgundy_dark)
                .layout(R.layout.fragment_simple_slide_large_image)
                .build());
    }
}

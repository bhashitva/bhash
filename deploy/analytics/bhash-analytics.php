<?php
/**
 * Plugin Name: Bhashitva Analytics
 * Description: Shared GA4 tag for public Bhashitva pages; no administrator visits.
 * Version: 1.0.0
 * License: Apache-2.0
 */
// SPDX-License-Identifier: Apache-2.0
if (!defined('ABSPATH')) { exit; }
add_action('wp_enqueue_scripts', function () {
    if (is_user_logged_in()) { return; }
    wp_enqueue_script('bhashitva-analytics', content_url('/mu-plugins/bhash-analytics.js'), array(), '1.0.0', false);
});

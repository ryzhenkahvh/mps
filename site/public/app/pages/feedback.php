<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

$lang = Site::$lang;
$kinds = Site::list('feedback_kinds');
// the app adds where the person is writing from: version, Android, phone
$source = preg_replace('/[^a-z0-9_-]/', '', strtolower(clean($_REQUEST['src'] ?? '', 32))) ?? '';
$version = preg_replace('/[^0-9A-Za-z._-]/', '', clean($_REQUEST['v'] ?? '', 32)) ?? '';
$android = preg_replace('/[^0-9A-Za-z._ -]/', '', clean($_REQUEST['android'] ?? '', 16)) ?? '';
$device = clean($_REQUEST['device'] ?? '', 80);
$kind = 'idea';
$text = '';
$contact = '';
$error = '';

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    $kind = isset($kinds[clean($_POST['kind'] ?? '', 20)]) ? clean($_POST['kind'], 20) : 'idea';
    $text = clean($_POST['text'] ?? '', 4000);
    $contact = clean($_POST['contact'] ?? '', 190);
    $guard = form_guard_problem($_POST);
    if ($guard === 'bot') {
        header('Location: ' . Site::url('feedback') . '?done=1', true, 303);
        exit;
    }
    if (mb_strlen($text) < 3) {
        $error = t('feedback_error_empty');
    } elseif ($guard === 'fast') {
        $error = t('form_error_fast');
    } elseif ($guard === 'stale') {
        $error = t('form_error_stale');
    } elseif (Store::recent('feedback', Site::visitor()) >= 20) {
        $error = t('form_error_many');
    } else {
        $saved = Store::add([
            'kind' => 'feedback', 'lang' => $lang, 'source' => $source !== '' ? $source : 'site', 'app_version' => $version,
            'device' => trim($device . ($android !== '' ? ' · Android ' . $android : '')),
            'answers' => json_encode(['kind' => $kind, 'text' => $text], JSON_UNESCAPED_UNICODE), 'contact' => $contact, 'visitor' => Site::visitor(),
        ]);
        if ($saved) {
            header('Location: ' . Site::url('feedback') . '?done=1', true, 303);
            exit;
        }
        $error = t('form_error_save');
    }
    http_response_code(422);
}

$done = isset($_GET['done']) && $error === '';
layout(t('feedback_title'), t('feedback_description'), static function () use ($kinds, $source, $version, $android, $device, $kind, $text, $contact, $error, $done): void {
    ?>
<section class="section first">
  <div class="wrap narrow">
    <h1><?= e(t('feedback_h1')) ?></h1>
    <?php if ($done): ?>
      <p class="lead"><?= e(t('feedback_done')) ?></p>
      <p><a href="<?= e(Site::url('survey')) ?>"><?= e(t('feedback_more')) ?></a></p>
      <a class="button primary" href="<?= e(Site::url('home')) ?>"><?= e(t('back_home')) ?></a>
    <?php else: ?>
      <p class="lead"><?= e(t('feedback_lead')) ?></p>
      <?php if ((string) (Site::$config['telegram'] ?? '') !== ''): ?>
        <p class="note"><?= e(t('feedback_telegram')) ?> <a href="https://t.me/<?= e((string) Site::$config['telegram']) ?>" rel="noopener">@<?= e((string) Site::$config['telegram']) ?></a></p>
      <?php endif; ?>
      <?php if ($source === 'app' && $version !== ''): ?>
        <p class="note"><?= e(t('feedback_from_app', $version, trim($device . ($android !== '' ? ', Android ' . $android : ''), ', '))) ?></p>
      <?php endif; ?>
      <p class="alert" role="alert" id="form-error" data-required="<?= e(t('feedback_error_empty')) ?>"<?= $error === '' ? ' hidden' : '' ?>><?= e($error) ?></p>
      <form method="post" action="<?= e(Site::url('feedback')) ?>" class="form" novalidate data-check>
        <?= form_guard() ?>
        <input type="hidden" name="src" value="<?= e($source) ?>">
        <input type="hidden" name="v" value="<?= e($version) ?>">
        <input type="hidden" name="android" value="<?= e($android) ?>">
        <input type="hidden" name="device" value="<?= e($device) ?>">
        <fieldset class="question">
          <legend><?= e(t('feedback_kind')) ?></legend>
          <div class="options row">
            <?php foreach ($kinds as $key => $label): ?>
              <label class="option"><input type="radio" name="kind" value="<?= e((string) $key) ?>"<?= $kind === $key ? ' checked' : '' ?>><span><?= e($label) ?></span></label>
            <?php endforeach; ?>
          </div>
        </fieldset>
        <div class="field<?= $error === t('feedback_error_empty') ? ' bad' : '' ?>" data-required>
          <label for="text"><?= e(t('feedback_text')) ?> <span class="star" aria-hidden="true">*</span></label>
          <textarea id="text" name="text" rows="6" maxlength="4000" required placeholder="<?= e(t('feedback_text_hint')) ?>"><?= e($text) ?></textarea>
        </div>
        <div class="field">
          <label for="contact"><?= e(t('feedback_contact')) ?> <span class="hint">(<?= e(t('optional')) ?>)</span></label>
          <input id="contact" type="text" name="contact" maxlength="190" value="<?= e($contact) ?>" placeholder="<?= e(t('feedback_contact_hint')) ?>" autocomplete="off">
        </div>
        <button class="button primary big" type="submit"><?= e(t('feedback_send')) ?></button>
        <p class="note form-note"><?= e(t('form_privacy')) ?> <a href="<?= e(Site::url('privacy')) ?>"><?= e(t('form_privacy_link')) ?></a>.</p>
      </form>
    <?php endif; ?>
  </div>
</section>
    <?php
});

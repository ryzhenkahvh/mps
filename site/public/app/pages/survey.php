<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

$questions = require __DIR__ . '/../survey.php';
$lang = Site::$lang;
$values = [];
$error = '';
$missing = [];
$source = preg_replace('/[^a-z0-9_-]/', '', strtolower(clean($_REQUEST['src'] ?? '', 32))) ?? '';
$contact = '';
$consent = false;

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    // only what the questions allow is kept: answers that are not among the options are dropped
    foreach ($questions as $q) {
        $id = $q['id'];
        $raw = $_POST[$id] ?? null;
        if ($q['type'] === 'one') {
            $v = clean($raw, 40);
            if (isset($q['options'][$v])) {
                $values[$id] = $v;
            }
        } elseif ($q['type'] === 'many') {
            $picked = array_values(array_filter(is_array($raw) ? array_map(static fn ($x) => clean($x, 40), $raw) : [], static fn ($x) => isset($q['options'][$x])));
            if ($picked) {
                $values[$id] = array_slice(array_values(array_unique($picked)), 0, $q['max'] ?? 20);
            }
        } elseif ($q['type'] === 'scale') {
            if (is_string($raw) && preg_match('/^\d{1,2}$/', $raw) === 1 && (int) $raw <= 10) {
                $values[$id] = (int) $raw;
            }
        } else {
            $v = clean($raw, 1500);
            if ($v !== '') {
                $values[$id] = $v;
            }
        }
        if (!empty($q['other'])) {
            $v = clean($_POST[$id . '_other'] ?? null, 200);
            if ($v !== '') {
                $values[$id . '_other'] = $v;
            }
        }
    }
    // an answer to a question that depends on another one counts only when that one was answered so
    foreach ($questions as $q) {
        foreach ($q['when'] ?? [] as $other => $needed) {
            if (($values[$other] ?? null) !== $needed) {
                unset($values[$q['id']], $values[$q['id'] . '_other']);
            }
        }
        if (!empty($q['required']) && !isset($values[$q['id']]) && !isset($values[$q['id'] . '_other'])) {
            $missing[] = $q['id'];
        }
    }
    $contact = clean($_POST['contact'] ?? '', 190);
    $consent = !empty($_POST['consent']);

    $guard = form_guard_problem($_POST);
    if ($guard === 'bot') {
        // say nothing useful to a script, and keep nothing
        header('Location: ' . Site::url('survey') . '?done=1', true, 303);
        exit;
    }
    if ($missing) {
        $error = t('form_error_required');
    } elseif ($contact !== '' && !$consent) {
        $error = t('form_error_consent');
    } elseif ($guard === 'fast') {
        $error = t('form_error_fast');
    } elseif ($guard === 'stale') {
        $error = t('form_error_stale');
    } elseif (Store::recent('survey', Site::visitor()) >= 20) {
        $error = t('form_error_many');
    } else {
        $saved = Store::add([
            'kind' => 'survey', 'lang' => $lang, 'source' => $source !== '' ? $source : 'site',
            'answers' => json_encode($values, JSON_UNESCAPED_UNICODE), 'contact' => $consent ? $contact : '', 'visitor' => Site::visitor(),
        ]);
        if ($saved) {
            header('Location: ' . Site::url('survey') . '?done=1', true, 303);
            exit;
        }
        $error = t('form_error_save');
    }
    http_response_code(422);
}

$done = isset($_GET['done']) && $error === '';
layout(t('survey_title'), t('survey_description'), static function () use ($questions, $lang, $values, $error, $missing, $source, $contact, $consent, $done): void {
    if ($done) {
        ?>
<section class="section first">
  <div class="wrap narrow">
    <h1><?= e(t('survey_done_h')) ?></h1>
    <p class="lead"><?= e(t('survey_done')) ?></p>
    <div class="actions">
      <a class="button primary" href="<?= e(Site::url('home')) ?>"><?= e(t('back_home')) ?></a>
      <a class="button ghost" href="<?= e(Site::url('stats')) ?>"><?= e(t('nav_stats')) ?></a>
    </div>
  </div>
</section>
        <?php
        return;
    }
    ?>
<section class="section first">
  <div class="wrap narrow">
    <h1><?= e(t('survey_h1')) ?></h1>
    <p class="lead"><?= e(t('survey_lead')) ?></p>
    <p class="alert" role="alert" id="form-error" data-required="<?= e(t('form_error_required')) ?>"<?= $error === '' ? ' hidden' : '' ?>><?= e($error) ?></p>
    <form method="post" action="<?= e(Site::url('survey')) ?>" class="form" novalidate data-check>
      <?= form_guard() ?>
      <input type="hidden" name="src" value="<?= e($source) ?>">
      <?php foreach ($questions as $n => $q):
          $id = $q['id'];
          $bad = in_array($id, $missing, true);
          $when = $q['when'] ?? [];
          $attrs = '';
          foreach ($when as $other => $needed) {
              $attrs .= ' data-when="' . e($other) . '" data-is="' . e($needed) . '"';
          }
          ?>
        <fieldset class="question<?= $bad ? ' bad' : '' ?>"<?= $attrs ?><?= !empty($q['required']) ? ' data-required' : '' ?>>
          <legend>
            <?= e($q['q'][$lang]) ?><?php if (!empty($q['required'])): ?> <span class="star" aria-hidden="true">*</span><?php endif; ?>
            <?php if ($q['type'] === 'many'): ?>
              <span class="hint">(<?= e(isset($q['max']) ? t('pick_max', (string) $q['max']) : t('pick_some')) ?>)</span>
            <?php endif; ?>
          </legend>
          <?php if ($q['type'] === 'one' || $q['type'] === 'many'): ?>
            <div class="options"<?= isset($q['max']) ? ' data-max="' . (int) $q['max'] . '"' : '' ?>>
              <?php foreach ($q['options'] as $key => $label):
                  $checked = $q['type'] === 'one' ? (($values[$id] ?? '') === $key) : in_array($key, $values[$id] ?? [], true);
                  ?>
                <label class="option">
                  <input type="<?= $q['type'] === 'one' ? 'radio' : 'checkbox' ?>" name="<?= e($id) ?><?= $q['type'] === 'many' ? '[]' : '' ?>" value="<?= e((string) $key) ?>"<?= $checked ? ' checked' : '' ?>>
                  <span><?= e($label[$lang]) ?></span>
                </label>
              <?php endforeach; ?>
            </div>
            <?php if (!empty($q['other'])): ?>
              <div class="other"><input type="text" name="<?= e($id) ?>_other" maxlength="200" value="<?= e($values[$id . '_other'] ?? '') ?>" placeholder="<?= e(t('other_label')) ?>" aria-label="<?= e(t('other_label')) ?>" autocomplete="off"></div>
            <?php endif; ?>
          <?php elseif ($q['type'] === 'scale'): ?>
            <div class="scale">
              <?php for ($i = 0; $i <= 10; $i++): ?>
                <label><input type="radio" name="<?= e($id) ?>" value="<?= $i ?>"<?= (($values[$id] ?? -1) === $i) ? ' checked' : '' ?>><span><?= $i ?></span></label>
              <?php endfor; ?>
            </div>
            <div class="scale-ends"><span>0 — <?= e($q['low'][$lang]) ?></span><span>10 — <?= e($q['high'][$lang]) ?></span></div>
          <?php else: ?>
            <textarea name="<?= e($id) ?>" rows="3" maxlength="1500" aria-label="<?= e($q['q'][$lang]) ?>"<?= isset($q['hint']) ? ' placeholder="' . e($q['hint'][$lang]) . '"' : '' ?>><?= e($values[$id] ?? '') ?></textarea>
          <?php endif; ?>
        </fieldset>
      <?php endforeach; ?>

      <fieldset class="question<?= $contact !== '' && !$consent && $error !== '' && !$missing ? ' bad' : '' ?>" data-consent="<?= e(t('form_error_consent')) ?>">
        <legend><?= e(t('contact_h')) ?> <span class="hint">(<?= e(t('optional')) ?>)</span></legend>
        <p class="note"><?= e(t('contact_lead')) ?></p>
        <input type="text" name="contact" maxlength="190" value="<?= e($contact) ?>" aria-label="<?= e(t('contact_label')) ?>" placeholder="<?= e(t('contact_label')) ?>" autocomplete="off">
        <label class="option consent"><input type="checkbox" name="consent" value="1"<?= $consent ? ' checked' : '' ?>><span><?= e(t('contact_consent')) ?></span></label>
      </fieldset>
      <button class="button primary big" type="submit"><?= e(t('survey_send')) ?></button>
      <p class="note form-note"><?= e(t('form_privacy')) ?> <a href="<?= e(Site::url('privacy')) ?>"><?= e(t('form_privacy_link')) ?></a>.</p>
    </form>
  </div>
</section>
    <?php
});

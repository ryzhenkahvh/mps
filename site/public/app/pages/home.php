<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/** Line icons of the feature cards, drawn on a 24 x 24 grid. */
function feature_icon(string $name): string
{
    $paths = [
        'steps' => 'M3 5h4v4H3zM10 5h4v4h-4zM17 5h4v4h-4zM3 15h4v4H3zM10 15h4v4h-4zM17 15h4v4h-4z',
        'piano' => 'M3 5h18v14H3zM8 5v14M13 5v14M18 5v14M6.5 5v8h3V5M11.5 5v8h3V5',
        'playlist' => 'M3 6h9M3 12h14M3 18h7M16 6h5M14 18h7',
        'mixer' => 'M6 3v18M12 3v18M18 3v18M4 8h4M10 15h4M16 10h4',
        'synth' => 'M2 12c2.5-8 5-8 7.5 0s5 8 7.5 0c1-3 2-4.8 5-5',
        'mic' => 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3',
        'export' => 'M12 3v12M7 10l5 5 5-5M4 19h16',
        'library' => 'M4 5h3v14H4zM10 5h3v14h-3zM15.5 6.5l3-.8 3 12.6-3 .8z',
    ];
    return '<svg class="icon" viewBox="0 0 24 24" width="28" height="28" aria-hidden="true"><path d="' . ($paths[$name] ?? '') . '"/></svg>';
}

$apk = Site::apk();
// a line of facts separated by " · " is broken only between the facts, never inside one
$facts = static fn (string $text): string => implode(' · ', array_map(static fn (string $part): string => '<span class="nowrap">' . e($part) . '</span>', explode(' · ', $text)));
layout(t('home_title'), t('home_description'), static function () use ($apk, $facts): void {
    ?>
<section class="hero">
  <div class="wrap hero-grid">
    <div class="hero-text">
      <h1><?= e(t('hero_h1')) ?></h1>
      <p class="lead"><?= e(t('hero_lead')) ?></p>
      <div class="actions">
        <?php if ($apk): ?>
          <a class="button primary" href="<?= e(Site::url('download')) ?>"><?= e(t('hero_download')) ?></a>
        <?php else: ?>
          <a class="button primary" href="<?= e(Site::url('survey')) ?>"><?= e(t('cta_button')) ?></a>
        <?php endif; ?>
        <a class="button ghost" href="#features"><?= e(t('hero_more')) ?></a>
      </div>
      <p class="note"><?= $apk ? $facts(t('hero_note', $apk['version'] ?: '1.0', $apk['megabytes'])) : e(t('hero_note_soon')) ?></p>
    </div>
    <div class="phone" role="img" aria-label="<?= e(t('hero_alt')) ?>">
      <img src="<?= e(Site::shot('playlist')) ?>" alt="" width="1600" height="738" fetchpriority="high">
    </div>
  </div>
</section>

<section id="features" class="section">
  <div class="wrap">
    <h2><?= e(t('features_h2')) ?></h2>
    <ul class="cards">
      <?php foreach (Site::list('features') as [$icon, $title, $text]): ?>
        <li class="card">
          <?= feature_icon($icon) ?>
          <h3><?= e($title) ?></h3>
          <p><?= e($text) ?></p>
        </li>
      <?php endforeach; ?>
    </ul>
  </div>
</section>

<section class="section tinted" aria-labelledby="shots-title">
  <div class="wrap">
    <div class="row-title">
      <h2 id="shots-title"><?= e(t('shots_h2')) ?></h2>
      <div class="arrows">
        <button type="button" class="arrow" data-scroll="-1" aria-label="<?= e(t('shots_prev')) ?>">‹</button>
        <button type="button" class="arrow" data-scroll="1" aria-label="<?= e(t('shots_next')) ?>">›</button>
      </div>
    </div>
  </div>
  <div class="gallery" tabindex="0">
    <?php foreach (Site::list('shots') as [$file, $caption]): ?>
      <figure>
        <a href="<?= e(Site::shot($file)) ?>"><img src="<?= e(Site::shot($file)) ?>" alt="<?= e($caption) ?>" width="1600" height="738" loading="lazy"></a>
        <figcaption><?= e($caption) ?></figcaption>
      </figure>
    <?php endforeach; ?>
  </div>
</section>

<section id="community" class="section">
  <div class="wrap two">
    <div>
      <h2><?= e(t('community_h2')) ?></h2>
      <p class="lead"><?= e(t('community_lead')) ?></p>
      <dl class="kinds">
        <?php foreach (Site::list('community') as [$title, $text]): ?>
          <div><dt><?= e($title) ?></dt><dd><?= e($text) ?></dd></div>
        <?php endforeach; ?>
      </dl>
      <p class="note"><?= e(t('community_note')) ?></p>
      <p class="note soon"><?= e(t('community_market')) ?></p>
    </div>
    <div class="phone small">
      <img src="<?= e(Site::shot('community')) ?>" alt="<?= e(t('community_alt')) ?>" width="1600" height="738" loading="lazy">
    </div>
  </div>
</section>

<section class="section cta">
  <div class="wrap narrow">
    <h2><?= e(t('cta_h2')) ?></h2>
    <p class="lead"><?= e(t('cta_lead')) ?></p>
    <div class="actions center">
      <a class="button light" href="<?= e(Site::url('survey')) ?>"><?= e(t('cta_button')) ?></a>
      <a class="button outline" href="<?= e(Site::url('feedback')) ?>"><?= e(t('cta_feedback')) ?></a>
    </div>
  </div>
</section>

<section class="section">
  <div class="wrap narrow">
    <h2><?= e(t('faq_h2')) ?></h2>
    <div class="faq">
      <?php foreach (Site::list('faq') as [$question, $answer]): ?>
        <details>
          <summary><?= e($question) ?></summary>
          <p><?= e($answer) ?></p>
        </details>
      <?php endforeach; ?>
    </div>
  </div>
</section>
<script type="application/ld+json"><?= json_encode([
    '@context' => 'https://schema.org',
    '@type' => 'SoftwareApplication',
    'name' => 'MPS',
    'operatingSystem' => 'Android 12+',
    'applicationCategory' => 'MusicApplication',
    'description' => t('home_description'),
    'url' => rtrim((string) Site::$config['site_url'], '/') . Site::url('home'),
], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_HEX_TAG) ?></script>
    <?php
});

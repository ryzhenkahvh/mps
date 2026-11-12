<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * Pure-PHP ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level M, Versions 1..10) outputting crisp SVG.
 * Needs no extensions or external libraries, and outputs zero inline CSS styles (CSP-safe).
 */
final class QrCode
{
    // [totalDataCodewords, ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data, remainderBits, alignCoords]
    private const SPECS = [
        1  => [16,  10, 1, 16, 0, 0, 0, []],
        2  => [28,  16, 1, 28, 0, 0, 7, [6, 18]],
        3  => [44,  26, 1, 44, 0, 0, 7, [6, 22]],
        4  => [64,  18, 2, 32, 0, 0, 7, [6, 26]],
        5  => [86,  24, 2, 43, 0, 0, 7, [6, 30]],
        6  => [108, 16, 4, 27, 0, 0, 7, [6, 34]],
        7  => [124, 18, 4, 31, 0, 0, 0, [6, 22, 38]],
        8  => [154, 22, 2, 38, 2, 39, 0, [6, 24, 42]],
        9  => [182, 22, 3, 36, 2, 37, 0, [6, 26, 46]],
        10 => [216, 26, 4, 43, 1, 44, 0, [6, 28, 50]],
    ];

    /** Encodes UTF-8 $text into a binary matrix (N x N array of bools: true = dark). */
    public static function matrix(string $text): array
    {
        $bytes = array_values(unpack('C*', $text) ?: []);
        $len = count($bytes);
        $ver = 0;
        foreach (self::SPECS as $v => $s) {
            $headerBits = ($v <= 9 ? 12 : 20); // 4 mode + (8 or 16) len
            $capBytes = intdiv($s[0] * 8 - $headerBits, 8);
            if ($len <= $capBytes) {
                $ver = $v;
                break;
            }
        }
        if ($ver === 0) {
            throw new InvalidArgumentException('QR payload too long');
        }

        [$totalData, $ecLen, $g1Blocks, $g1Data, $g2Blocks, $g2Data, $remBits, $align] = self::SPECS[$ver];

        // 1. Data bitstream (Byte mode 0100)
        $bits = [];
        self::pushBits($bits, 0b0100, 4);
        self::pushBits($bits, $len, $ver <= 9 ? 8 : 16);
        foreach ($bytes as $b) {
            self::pushBits($bits, $b, 8);
        }
        $capBits = $totalData * 8;
        $term = min(4, $capBits - count($bits));
        self::pushBits($bits, 0, $term);
        while (count($bits) % 8 !== 0) {
            $bits[] = 0;
        }
        $pad = [0xEC, 0x11];
        $pi = 0;
        while (count($bits) < $capBits) {
            self::pushBits($bits, $pad[$pi & 1], 8);
            $pi++;
        }

        $codewords = [];
        for ($i = 0; $i < $capBits; $i += 8) {
            $v = 0;
            for ($j = 0; $j < 8; $j++) {
                $v = ($v << 1) | $bits[$i + $j];
            }
            $codewords[] = $v;
        }

        // 2. Split into blocks + Reed-Solomon EC codewords
        $blocks = [];
        $ecs = [];
        $off = 0;
        $rsDiv = self::rsDivisor($ecLen);
        for ($b = 0; $b < $g1Blocks + $g2Blocks; $b++) {
            $dLen = $b < $g1Blocks ? $g1Data : $g2Data;
            $blk = array_slice($codewords, $off, $dLen);
            $off += $dLen;
            $blocks[] = $blk;
            $ecs[] = self::rsRemainder($blk, $rsDiv);
        }

        // 3. Interleave data + EC codewords into final bitstream
        $finalBits = [];
        $maxD = max($g1Data, $g2Data);
        for ($i = 0; $i < $maxD; $i++) {
            foreach ($blocks as $blk) {
                if (isset($blk[$i])) {
                    self::pushBits($finalBits, $blk[$i], 8);
                }
            }
        }
        for ($i = 0; $i < $ecLen; $i++) {
            foreach ($ecs as $ec) {
                self::pushBits($finalBits, $ec[$i], 8);
            }
        }
        for ($i = 0; $i < $remBits; $i++) {
            $finalBits[] = 0;
        }

        // 4. Function patterns
        $size = 17 + 4 * $ver;
        $grid = array_fill(0, $size, array_fill(0, $size, false));
        $func = array_fill(0, $size, array_fill(0, $size, false));

        self::drawFinder($grid, $func, 0, 0, $size);
        self::drawFinder($grid, $func, $size - 7, 0, $size);
        self::drawFinder($grid, $func, 0, $size - 7, $size);

        $nAlign = count($align);
        for ($i = 0; $i < $nAlign; $i++) {
            for ($j = 0; $j < $nAlign; $j++) {
                if (!(($i === 0 && $j === 0) || ($i === 0 && $j === $nAlign - 1) || ($i === $nAlign - 1 && $j === 0))) {
                    self::drawAlignment($grid, $func, $align[$i], $align[$j]);
                }
            }
        }

        for ($i = 8; $i < $size - 8; $i++) {
            $dark = ($i % 2 === 0);
            $grid[6][$i] = $dark;
            $func[6][$i] = true;
            $grid[$i][6] = $dark;
            $func[$i][6] = true;
        }

        // Reserve format info + dark module
        for ($i = 0; $i <= 8; $i++) {
            if ($i !== 6) {
                $func[8][$i] = true;
                $func[$i][8] = true;
            }
        }
        for ($i = 0; $i < 8; $i++) {
            $func[8][$size - 1 - $i] = true;
            $func[$size - 1 - $i][8] = true;
        }
        $grid[$size - 8][8] = true;
        $func[$size - 8][8] = true;

        if ($ver >= 7) {
            self::drawVersion($grid, $func, $ver, $size);
        }

        // 5. Place data bits (zigzag upwards/downwards)
        $bi = 0;
        $totalBits = count($finalBits);
        for ($right = $size - 1; $right >= 1; $right -= 2) {
            if ($right === 6) {
                $right = 5;
            }
            for ($vert = 0; $vert < $size; $vert++) {
                for ($j = 0; $j < 2; $j++) {
                    $x = $right - $j;
                    $up = ((($right + 1) & 2) === 0);
                    $y = $up ? ($size - 1 - $vert) : $vert;
                    if (!$func[$y][$x] && $bi < $totalBits) {
                        $grid[$y][$x] = ($finalBits[$bi] === 1);
                        $bi++;
                    }
                }
            }
        }

        // 6. Pick best mask (0..7)
        $bestMask = 0;
        $bestScore = PHP_INT_MAX;
        $bestGrid = $grid;
        for ($m = 0; $m < 8; $m++) {
            $cand = $grid;
            self::applyMask($cand, $func, $m, $size);
            self::drawFormat($cand, $m, $size);
            $score = self::penalty($cand, $size);
            if ($score < $bestScore) {
                $bestScore = $score;
                $bestMask = $m;
                $bestGrid = $cand;
            }
        }

        return $bestGrid;
    }

    /** Renders a crisp SVG QR code with white quiet zone (margin modules) and dark modules. */
    public static function svg(string $text, string $alt, string $class = 'qr-svg', int $quiet = 4): string
    {
        $m = self::matrix($text);
        $n = count($m);
        $full = $n + $quiet * 2;
        $path = '';
        for ($y = 0; $y < $n; $y++) {
            $x = 0;
            while ($x < $n) {
                if (!$m[$y][$x]) {
                    $x++;
                    continue;
                }
                $run = 1;
                while ($x + $run < $n && $m[$y][$x + $run]) {
                    $run++;
                }
                $px = $x + $quiet;
                $py = $y + $quiet;
                $path .= "M{$px} {$py}h{$run}v1h-{$run}z";
                $x += $run;
            }
        }
        return '<svg xmlns="http://www.w3.org/2000/svg" class="' . e($class) . '" viewBox="0 0 ' . $full . ' ' . $full . '" shape-rendering="crispEdges" role="img" aria-label="' . e($alt) . '">'
            . '<rect width="' . $full . '" height="' . $full . '" fill="#ffffff" rx="1.5"/>'
            . '<path fill="#130b10" d="' . $path . '"/>'
            . '</svg>';
    }

    private static function pushBits(array &$bits, int $val, int $len): void
    {
        for ($i = $len - 1; $i >= 0; $i--) {
            $bits[] = ($val >> $i) & 1;
        }
    }

    private static function drawFinder(array &$grid, array &$func, int $x0, int $y0, int $size): void
    {
        for ($dy = -1; $dy <= 7; $dy++) {
            for ($dx = -1; $dx <= 7; $dx++) {
                $x = $x0 + $dx;
                $y = $y0 + $dy;
                if ($x < 0 || $y < 0 || $x >= $size || $y >= $size) {
                    continue;
                }
                $dist = max(abs($dx - 3), abs($dy - 3));
                $grid[$y][$x] = ($dist !== 2 && $dist <= 3);
                $func[$y][$x] = true;
            }
        }
    }

    private static function drawAlignment(array &$grid, array &$func, int $cx, int $cy): void
    {
        for ($dy = -2; $dy <= 2; $dy++) {
            for ($dx = -2; $dx <= 2; $dx++) {
                $dist = max(abs($dx), abs($dy));
                $grid[$cy + $dy][$cx + $dx] = ($dist !== 1);
                $func[$cy + $dy][$cx + $dx] = true;
            }
        }
    }

    private static function drawFormat(array &$grid, int $mask, int $size): void
    {
        // ECC Level M = 00
        $data = (0b00 << 3) | $mask;
        $rem = $data;
        for ($i = 0; $i < 10; $i++) {
            $rem = ($rem << 1) ^ ((($rem >> 9) & 1) * 0x537);
        }
        $bits = (($data << 10) | $rem) ^ 0x5412;

        for ($i = 0; $i <= 5; $i++) {
            $grid[$i][8] = (($bits >> $i) & 1) === 1;
        }
        $grid[7][8] = (($bits >> 6) & 1) === 1;
        $grid[8][8] = (($bits >> 7) & 1) === 1;
        $grid[8][7] = (($bits >> 8) & 1) === 1;
        for ($i = 9; $i < 15; $i++) {
            $grid[8][14 - $i] = (($bits >> $i) & 1) === 1;
        }
        for ($i = 0; $i < 8; $i++) {
            $grid[8][$size - 1 - $i] = (($bits >> $i) & 1) === 1;
        }
        for ($i = 8; $i < 15; $i++) {
            $grid[$size - 15 + $i][8] = (($bits >> $i) & 1) === 1;
        }
    }

    private static function drawVersion(array &$grid, array &$func, int $ver, int $size): void
    {
        $rem = $ver;
        for ($i = 0; $i < 12; $i++) {
            $rem = ($rem << 1) ^ ((($rem >> 11) & 1) * 0x1F25);
        }
        $bits = ($ver << 12) | $rem;
        for ($i = 0; $i < 18; $i++) {
            $bit = (($bits >> $i) & 1) === 1;
            $a = $size - 11 + ($i % 3);
            $b = intdiv($i, 3);
            $grid[$b][$a] = $bit;
            $func[$b][$a] = true;
            $grid[$a][$b] = $bit;
            $func[$a][$b] = true;
        }
    }

    private static function applyMask(array &$grid, array $func, int $mask, int $size): void
    {
        for ($y = 0; $y < $size; $y++) {
            for ($x = 0; $x < $size; $x++) {
                if ($func[$y][$x]) {
                    continue;
                }
                $invert = match ($mask) {
                    0 => (($x + $y) % 2) === 0,
                    1 => ($y % 2) === 0,
                    2 => ($x % 3) === 0,
                    3 => (($x + $y) % 3) === 0,
                    4 => ((intdiv($y, 2) + intdiv($x, 3)) % 2) === 0,
                    5 => (($x * $y) % 2 + ($x * $y) % 3) === 0,
                    6 => ((($x * $y) % 2 + ($x * $y) % 3) % 2) === 0,
                    default => ((($x + $y) % 2 + ($x * $y) % 3) % 2) === 0,
                };
                if ($invert) {
                    $grid[$y][$x] = !$grid[$y][$x];
                }
            }
        }
    }

    private static function penalty(array $grid, int $size): int
    {
        $score = 0;
        // N1 + N3 on rows and cols
        for ($y = 0; $y < $size; $y++) {
            $runH = 0;
            $runV = 0;
            $prevH = false;
            $prevV = false;
            $histH = [0, 0, 0, 0, 0, 0, 0];
            $histV = [0, 0, 0, 0, 0, 0, 0];
            for ($x = 0; $x < $size; $x++) {
                // horizontal
                $ch = $grid[$y][$x];
                if ($ch === $prevH) {
                    $runH++;
                } else {
                    if ($runH >= 5) {
                        $score += $runH - 2;
                    }
                    $score += self::n3Check($histH, $runH, $prevH);
                    $prevH = $ch;
                    $runH = 1;
                }
                // vertical
                $cv = $grid[$x][$y];
                if ($cv === $prevV) {
                    $runV++;
                } else {
                    if ($runV >= 5) {
                        $score += $runV - 2;
                    }
                    $score += self::n3Check($histV, $runV, $prevV);
                    $prevV = $cv;
                    $runV = 1;
                }
            }
            if ($runH >= 5) {
                $score += $runH - 2;
            }
            $score += self::n3Check($histH, $runH, $prevH);
            if ($runV >= 5) {
                $score += $runV - 2;
            }
            $score += self::n3Check($histV, $runV, $prevV);
        }
        // N2: 2x2 blocks
        for ($y = 0; $y < $size - 1; $y++) {
            for ($x = 0; $x < $size - 1; $x++) {
                $c = $grid[$y][$x];
                if ($c === $grid[$y][$x + 1] && $c === $grid[$y + 1][$x] && $c === $grid[$y + 1][$x + 1]) {
                    $score += 3;
                }
            }
        }
        // N4: dark ratio
        $dark = 0;
        foreach ($grid as $row) {
            foreach ($row as $cell) {
                if ($cell) {
                    $dark++;
                }
            }
        }
        $k = intdiv(abs($dark * 20 - $size * $size * 10), $size * $size);
        return $score + $k * 10;
    }

    private static function n3Check(array &$hist, int $run, bool $wasDark): int
    {
        if ($run === 0) {
            return 0;
        }
        array_shift($hist);
        $hist[] = $run;
        // pattern 1:1:3:1:1 dark:light:dark:light:dark with >=4 light on either side
        if (!$wasDark) {
            // light run just ended, check if hist = [?, B1, W1, B3, W1, B1, W>=4]
            if ($hist[6] >= 4 && $hist[5] > 0 && $hist[5] === $hist[1] && $hist[4] === $hist[1] && $hist[2] === $hist[1] && $hist[3] === $hist[1] * 3) {
                return 40;
            }
        }
        return 0;
    }

    private static function gfMul(int $x, int $y): int
    {
        $z = 0;
        for ($i = 7; $i >= 0; $i--) {
            $z = (($z << 1) ^ (($z >> 7) * 0x11D)) ^ ((($y >> $i) & 1) * $x);
        }
        return $z;
    }

    private static function rsDivisor(int $deg): array
    {
        $res = array_fill(0, $deg, 0);
        $res[$deg - 1] = 1;
        $root = 1;
        for ($i = 0; $i < $deg; $i++) {
            for ($j = 0; $j < $deg; $j++) {
                $res[$j] = self::gfMul($res[$j], $root);
                if ($j + 1 < $deg) {
                    $res[$j] ^= $res[$j + 1];
                }
            }
            $root = self::gfMul($root, 0x02);
        }
        return $res;
    }

    private static function rsRemainder(array $data, array $div): array
    {
        $res = array_fill(0, count($div), 0);
        foreach ($data as $b) {
            $factor = $b ^ array_shift($res);
            $res[] = 0;
            foreach ($div as $i => $d) {
                $res[$i] ^= self::gfMul($d, $factor);
            }
        }
        return $res;
    }
}

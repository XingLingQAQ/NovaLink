<?php
// build-plugin-phar.php -- package the NovaChat PMMP plugin source tree into a
// loadable .phar that PocketMine-MP's PharPluginLoader will accept.
//
// Usage: php build-plugin-phar.php <src-dir> <out-phar>
//   <src-dir>  = NovaChat/Bedrock/pmmp (must contain plugin.yml + src/ + resources/)
//   <out-phar> = destination .phar path (e.g. plugins/NovaChat.phar)
//
// PocketMine-MP's PharPluginLoader::canLoadPlugin requires is_file() + .phar
// suffix, and getPluginDescription() reads phar://.../plugin.yml. Source-dir
// plugins (folder + plugin.yml) are NOT loaded by PMMP 5.x (no FolderPluginLoader
// ships with the server), so we must package the source into a phar.

$srcDir = $argv[1] ?? null;
$outPhar = $argv[2] ?? null;
if (!$srcDir || !is_dir($srcDir)) {
    fwrite(STDERR, "Usage: php build-plugin-phar.php <src-dir> <out-phar>\n");
    fwrite(STDERR, "  <src-dir> must exist and contain plugin.yml\n");
    exit(1);
}
if (!file_exists("$srcDir/plugin.yml")) {
    fwrite(STDERR, "ERROR: $srcDir/plugin.yml not found -- cannot build phar without a manifest\n");
    exit(1);
}
if (!$outPhar) {
    fwrite(STDERR, "ERROR: output phar path not specified\n");
    exit(1);
}

// Phar.write operations require phar.readonly = 0. Bail with a clear message
// if the host PHP has phar.readonly = 1 (the default) so the caller knows to
// pass -d phar.readonly=0.
if (ini_get('phar.readonly')) {
    fwrite(STDERR, "ERROR: phar.readonly is ON. Re-run with: php -d phar.readonly=0 build-plugin-phar.php ...\n");
    exit(1);
}

@mkdir(dirname($outPhar), 0777, true);
if (file_exists($outPhar)) { unlink($outPhar); }

$phar = new Phar($outPhar);
$phar->startBuffering();

// Add the entire source tree (plugin.yml + src/ + resources/ + anything else).
// Phar::buildFromDirectory recurses and strips the source prefix.
$phar->buildFromDirectory($srcDir);

// Stub: a minimal loader stub. PMMP's PharPluginLoader reads plugin.yml from
// the phar root and registers the src/ namespace via composer-style autoload --
// it does NOT execute the stub. Keep it minimal + phar-safe.
$stub = '<?php __HALT_COMPILER();';
$phar->setStub($stub);

$phar->stopBuffering();

// Report what was packaged.
$files = new RecursiveIteratorIterator($phar);
$count = 0;
foreach ($files as $f) { $count++; }
$size = filesize($outPhar);
echo "Built $outPhar ($count files, " . round($size / 1024, 1) . " KB)\n";
echo "  plugin.yml present: " . (isset($phar['plugin.yml']) ? 'yes' : 'NO') . "\n";

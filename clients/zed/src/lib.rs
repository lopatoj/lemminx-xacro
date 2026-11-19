//! Zed extension: runs the stock Eclipse LemMinX (Java) with the lemminx-xacro extension jar
//! on its classpath, for the "Xacro" language (*.xacro, *.urdf).
//!
//! Command: `java -cp <lemminx-uber.jar><sep><lemminx-xacro.jar> org.eclipse.lemminx.XMLServerLauncher`
//!
//! - LemMinX is downloaded from Eclipse's Maven repository at a pinned version and checked
//!   against a pinned SHA-256.
//! - lemminx-xacro is downloaded from this repository's latest GitHub release.
//! - `lsp.lemminx-xacro.binary` in Zed settings overrides everything (useful for local builds).
//! - `lsp.lemminx-xacro.settings` is passed to LemMinX (e.g. `{"xml": {"xacro": {...}}}`).

use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use zed::LanguageServerId;
use zed_extension_api::{self as zed, serde_json::Value, settings::LspSettings, Result};

const LS_NAME: &str = "lemminx-xacro";

const LEMMINX_VERSION: &str = "0.31.2";
const LEMMINX_SHA256: &str = "f4fde164e785c635e5f86361dbf4b993bfe2c5f83fdb52891d058b7dfc9bcfc8";
const LEMMINX_MAIN: &str = "org.eclipse.lemminx.XMLServerLauncher";

const EXT_REPO: &str = "lopatoj/lemminx-xacro";

fn lemminx_url() -> String {
    format!(
        "https://repo.eclipse.org/content/repositories/lemminx-releases/org/eclipse/lemminx/\
         org.eclipse.lemminx/{v}/org.eclipse.lemminx-{v}-uber.jar",
        v = LEMMINX_VERSION
    )
}

struct XacroExtension {
    cached_classpath: Option<(String, String)>,
}

impl XacroExtension {
    /// Absolute paths of (lemminx uber jar, lemminx-xacro jar), downloading them if needed.
    fn classpath(&mut self, id: &LanguageServerId) -> Result<(String, String)> {
        if let Some((a, b)) = &self.cached_classpath {
            if is_file(a) && is_file(b) {
                return Ok((a.clone(), b.clone()));
            }
        }
        let lemminx = install_lemminx(id)?;
        let xacro = install_xacro_jar(id)?;
        zed::set_language_server_installation_status(
            id,
            &zed::LanguageServerInstallationStatus::None,
        );
        let paths = (absolute(&lemminx)?, absolute(&xacro)?);
        self.cached_classpath = Some(paths.clone());
        Ok(paths)
    }
}

// ------------------------------------------------------------------ downloads

fn install_lemminx(id: &LanguageServerId) -> Result<String> {
    let dir = format!("lemminx-{LEMMINX_VERSION}");
    let jar = format!("{dir}/org.eclipse.lemminx-{LEMMINX_VERSION}-uber.jar");
    if is_file(&jar) {
        return Ok(jar);
    }
    zed::set_language_server_installation_status(
        id,
        &zed::LanguageServerInstallationStatus::Downloading,
    );
    let tmp = format!("{dir}.download");
    fs::remove_dir_all(&tmp).ok();
    fs::create_dir_all(&tmp).map_err(|e| format!("failed to create {tmp}: {e}"))?;
    let tmp_jar = format!("{tmp}/org.eclipse.lemminx-{LEMMINX_VERSION}-uber.jar");
    zed::download_file(&lemminx_url(), &tmp_jar, zed::DownloadedFileType::Uncompressed)
        .map_err(|e| format!("failed to download LemMinX {LEMMINX_VERSION}: {e}"))?;
    let actual = sha256_file(&tmp_jar)?;
    if actual != LEMMINX_SHA256 {
        fs::remove_dir_all(&tmp).ok();
        return Err(format!(
            "checksum mismatch for LemMinX {LEMMINX_VERSION}: expected {LEMMINX_SHA256}, got {actual}"
        ));
    }
    fs::remove_dir_all(&dir).ok();
    fs::rename(&tmp, &dir).map_err(|e| format!("failed to move {tmp} to {dir}: {e}"))?;
    remove_other_dirs("lemminx-", &dir);
    Ok(jar)
}

fn install_xacro_jar(id: &LanguageServerId) -> Result<String> {
    zed::set_language_server_installation_status(
        id,
        &zed::LanguageServerInstallationStatus::CheckingForUpdate,
    );
    let release = match zed::latest_github_release(
        EXT_REPO,
        zed::GithubReleaseOptions {
            require_assets: true,
            pre_release: false,
        },
    ) {
        Ok(r) => r,
        // offline / rate limited: fall back to the newest jar we already have
        Err(e) => return newest_installed_xacro_jar().ok_or(e),
    };
    // the thin extension jar, not the "-uber" one
    let asset = release
        .assets
        .iter()
        .find(|a| {
            a.name.starts_with("lemminx-xacro-") && a.name.ends_with(".jar") && !a.name.contains("uber")
        })
        .ok_or_else(|| {
            format!(
                "release {} of {EXT_REPO} has no lemminx-xacro-<version>.jar asset",
                release.version
            )
        })?;
    let dir = format!("xacro-{}", release.version);
    let jar = format!("{dir}/{}", asset.name);
    if is_file(&jar) {
        return Ok(jar);
    }
    zed::set_language_server_installation_status(
        id,
        &zed::LanguageServerInstallationStatus::Downloading,
    );
    fs::create_dir_all(&dir).map_err(|e| format!("failed to create {dir}: {e}"))?;
    zed::download_file(&asset.download_url, &jar, zed::DownloadedFileType::Uncompressed)
        .map_err(|e| format!("failed to download {}: {e}", asset.name))?;
    remove_other_dirs("xacro-", &dir);
    Ok(jar)
}

fn newest_installed_xacro_jar() -> Option<String> {
    let mut dirs: Vec<String> = fs::read_dir(".")
        .ok()?
        .filter_map(|e| e.ok()?.file_name().into_string().ok())
        .filter(|n| n.starts_with("xacro-") && !n.ends_with(".download"))
        .collect();
    dirs.sort();
    let dir = dirs.pop()?;
    fs::read_dir(&dir)
        .ok()?
        .filter_map(|e| e.ok()?.file_name().into_string().ok())
        .find(|n| n.ends_with(".jar"))
        .map(|n| format!("{dir}/{n}"))
}

fn remove_other_dirs(prefix: &str, keep: &str) {
    if let Ok(entries) = fs::read_dir(".") {
        for entry in entries.flatten() {
            if let Ok(name) = entry.file_name().into_string() {
                if name.starts_with(prefix) && name != keep {
                    fs::remove_dir_all(entry.path()).ok();
                }
            }
        }
    }
}

fn sha256_file(path: &str) -> Result<String> {
    let mut file = fs::File::open(path).map_err(|e| format!("failed to open {path}: {e}"))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).map_err(|e| format!("failed to read {path}: {e}"))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect())
}

fn is_file(path: &str) -> bool {
    fs::metadata(path).map(|m| m.is_file()).unwrap_or(false)
}

/// Downloads live in the extension's work directory; java needs absolute paths.
fn absolute(path: &str) -> Result<String> {
    let cwd = std::env::current_dir().map_err(|e| format!("no working directory: {e}"))?;
    Ok(cwd.join(path).to_string_lossy().into_owned())
}

// ------------------------------------------------------------------ java

fn find_java(worktree: &zed::Worktree) -> Result<String> {
    let env = worktree.shell_env();
    if let Some((_, home)) = env.iter().find(|(k, _)| k == "JAVA_HOME") {
        let exe = match zed::current_platform().0 {
            zed::Os::Windows => "java.exe",
            _ => "java",
        };
        let candidate = format!("{}/bin/{exe}", home.trim_end_matches(['/', '\\']));
        return Ok(candidate);
    }
    worktree.which("java").ok_or_else(|| {
        "Java 17+ is required for LemMinX extensions but `java` was not found on PATH \
         (or set JAVA_HOME). Alternatively set `lsp.lemminx-xacro.binary` in Zed settings."
            .to_string()
    })
}

// ------------------------------------------------------------------ settings

fn workspace_settings(worktree: &zed::Worktree) -> Value {
    LspSettings::for_worktree(LS_NAME, worktree)
        .ok()
        .and_then(|s| s.settings)
        .unwrap_or_else(|| Value::Object(Default::default()))
}

/// LemMinX reads its start-up settings from `initializationOptions.settings`.
fn initialization_options(user: Option<Value>, settings: Value) -> Value {
    let mut options = match user {
        Some(Value::Object(map)) => Value::Object(map),
        _ => Value::Object(Default::default()),
    };
    if let Value::Object(map) = &mut options {
        map.entry("settings").or_insert(settings);
    }
    options
}

impl zed::Extension for XacroExtension {
    fn new() -> Self {
        Self {
            cached_classpath: None,
        }
    }

    fn language_server_command(
        &mut self,
        id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let binary = LspSettings::for_worktree(LS_NAME, worktree)
            .ok()
            .and_then(|s| s.binary);
        if let Some(path) = binary.as_ref().and_then(|b| b.path.clone()) {
            let binary = binary.unwrap();
            return Ok(zed::Command {
                command: path,
                args: binary.arguments.unwrap_or_default(),
                env: binary.env.unwrap_or_default().into_iter().collect(),
            });
        }

        let java = find_java(worktree)?;
        let (lemminx, xacro) = self.classpath(id).map_err(|e| {
            zed::set_language_server_installation_status(
                id,
                &zed::LanguageServerInstallationStatus::Failed(e.clone()),
            );
            e
        })?;
        let sep = match zed::current_platform().0 {
            zed::Os::Windows => ";",
            _ => ":",
        };
        let mut args = binary
            .as_ref()
            .and_then(|b| b.arguments.clone())
            .unwrap_or_default(); // extra JVM args, e.g. ["-Xmx1g"]
        args.extend([
            "-cp".to_string(),
            format!("{lemminx}{sep}{xacro}"),
            LEMMINX_MAIN.to_string(),
        ]);
        Ok(zed::Command {
            command: java,
            args,
            env: binary
                .and_then(|b| b.env)
                .unwrap_or_default()
                .into_iter()
                .collect(),
        })
    }

    fn language_server_initialization_options(
        &mut self,
        _id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<Value>> {
        let user = LspSettings::for_worktree(LS_NAME, worktree)
            .ok()
            .and_then(|s| s.initialization_options);
        Ok(Some(initialization_options(user, workspace_settings(worktree))))
    }

    fn language_server_workspace_configuration(
        &mut self,
        _id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<Value>> {
        Ok(Some(workspace_settings(worktree)))
    }
}

zed::register_extension!(XacroExtension);

#[cfg(test)]
mod tests {
    use super::*;
    use zed_extension_api::serde_json::json;

    #[test]
    fn user_initialization_options_are_kept_and_settings_added() {
        let opts = initialization_options(Some(json!({"x": 1})), json!({"xml": {}}));
        assert_eq!(opts, json!({"x": 1, "settings": {"xml": {}}}));
        let opts = initialization_options(Some(json!({"settings": {"a": 1}})), json!({"b": 2}));
        assert_eq!(opts, json!({"settings": {"a": 1}}));
    }

    #[test]
    fn lemminx_url_is_versioned() {
        assert!(lemminx_url().ends_with("/0.31.2/org.eclipse.lemminx-0.31.2-uber.jar"));
    }
}

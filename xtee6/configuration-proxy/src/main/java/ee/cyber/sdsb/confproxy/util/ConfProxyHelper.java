package ee.cyber.sdsb.confproxy.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.FileUtils;

import ee.cyber.sdsb.common.SystemProperties;
import ee.cyber.sdsb.common.conf.globalconf.ConfigurationDirectory;
import ee.cyber.sdsb.confproxy.ConfProxyProperties;

/**
 *Provides configuration proxy utility functions.
 */
@Slf4j
public final class ConfProxyHelper {
    private static final int SUCCESS = 0;
    private static final int ERROR_CODE_INTERNAL = 125;
    private static final int ERROR_CODE_INVALID_SIGNATURE_VALUE = 124;
    private static final int ERROR_CODE_EXPIRED_CONF = 123;
    private static final int ERROR_CODE_CANNOT_DOWNLOAD_CONF = 122;
    private static final int MAX_CONFIGURATION_LIFETIME_SECONDS = 600;

    private ConfProxyHelper() {
        
    }

    /**
     * Invoke the configuration client script to download the global
     * configuration from the source defined in the provided source anchor.
     * @param path where the downloaded files should be placed
     * @param sourceAnchor path to the source anchor xml file
     * @return downloaded configuration directory
     * @throws Exception if an configuration client error occurs
     */
    public static ConfigurationDirectory downloadConfiguration(String path,
            String sourceAnchor) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ConfProxyProperties.getDownloadScriptPath(),
                sourceAnchor, path);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        log.debug("Running '{} {} {}' ...", new String [] {
                ConfProxyProperties.getDownloadScriptPath(),
                sourceAnchor, path });

        runConfClient(pb);
        return new ConfigurationDirectory(path);
    }

    /**
     * Invoke the configuration client script to check whether the downloaded
     * global configuration is valid according to the provided source anchor.
     * @param sourceAnchor path to the source anchor xml file
     * @throws Exception if an configuration client error occurs
     */
    public static void validateConfiguration(String sourceAnchor)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ConfProxyProperties.getDownloadScriptPath(),
                sourceAnchor);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        log.info("Running '{} {}' ...", new String [] {
                ConfProxyProperties.getDownloadScriptPath(),
                sourceAnchor});
        runConfClient(pb);
    }

    private static void runConfClient(ProcessBuilder pb) throws Exception {
        int exitCode = -1;
        try {
            Process process = pb.start();
            exitCode = process.waitFor();
        } catch (IOException e) {
            exitCode = 2;
        } catch (Exception e) {
            //undetermined ConfigurationClient exitCode, fail in 'finally'
            return;
        } finally {
            switch (exitCode) {
            case SUCCESS:
                break;
            case ERROR_CODE_CANNOT_DOWNLOAD_CONF:
                throw new Exception("configuration-client error (exit code "
                        + exitCode + "), download failed");
            case ERROR_CODE_EXPIRED_CONF:
                throw new Exception("configuration-client error (exit code "
                        + exitCode + "), configuration is outdated");
            case ERROR_CODE_INVALID_SIGNATURE_VALUE:
                throw new Exception("configuration-client error (exit code "
                        + exitCode + "), configuration is incorrect");
            case ERROR_CODE_INTERNAL:
                throw new Exception("configuration-client error (exit code "
                        + exitCode + ")");
            default:
                throw new Exception("Failed to download GlobalConf "
                        + "(configucation-client exit code " + exitCode + "), "
                        + "make sure configuration-client is installed correctly");
            }
        }
    }

    /**
     * Gets all existing subdirectory names from the configuration proxy
     * configuration directory, which correspond to the configuration proxy
     * instance ids.
     * @return list of configuration proxy instance ids
     * @throws IOException if the configuration proxy configuration path is
     * erroneous
     */
    public static List<String> availableInstances() throws IOException {
        Path confPath =
            Paths.get(SystemProperties.getConfigurationProxyConfPath());
        return subDirectoryNames(confPath);
    }

    /**
     * Deletes outdated previously generated global configurations, as defined by
     * the 'validity interval' configuration proxy property.
     * @param conf the configuration proxy instance configuration
     * @throws IOException
     * in case an old global configuration could not be deleted
     */
    public static void purgeOutdatedGenerations(ConfProxyProperties conf)
            throws IOException {
        Path instanceDir = Paths.get(conf.getConfigurationTargetPath());
        Files.createDirectories(instanceDir); //avoid errors if it's not present
        for (String genTime : subDirectoryNames(instanceDir)) {
            Date current = new Date();
            Date old = new Date(Long.parseLong(genTime));
            long diffSeconds = TimeUnit.MILLISECONDS
                    .toSeconds((current.getTime() - old.getTime()));
            long timeToKeep = Math.min(MAX_CONFIGURATION_LIFETIME_SECONDS,
                    conf.getValidityIntervalSeconds());
            if (diffSeconds > timeToKeep) {
                Path oldPath =
                        Paths.get(conf.getConfigurationTargetPath(), genTime);
                FileUtils.deleteDirectory(oldPath.toFile());
            } else {
                Path valid = instanceDir.resolve(genTime);
                log.debug("A valid generated configuration exists in '{}'",
                        valid);
            }
        }
    }

    private static List<String> subDirectoryNames(Path dir) throws IOException {
        List<String> subdirs = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path subDir : stream) {
                String conf = subDir.getFileName().toString();
                subdirs.add(conf);
            }
            return subdirs;
        }
    }
}

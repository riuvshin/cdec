/*
 * CODENVY CONFIDENTIAL
 * __________________
 *
 *  [2012] - [2015] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.im.artifacts.helper;

import com.codenvy.im.artifacts.CDECArtifact;
import com.codenvy.im.commands.CheckInstalledVersionCommand;
import com.codenvy.im.commands.Command;
import com.codenvy.im.commands.CommandLibrary;
import com.codenvy.im.commands.MacroCommand;
import com.codenvy.im.commands.decorators.PuppetErrorInterrupter;
import com.codenvy.im.managers.BackupConfig;
import com.codenvy.im.managers.Config;
import com.codenvy.im.managers.ConfigManager;
import com.codenvy.im.managers.InstallOptions;
import com.codenvy.im.utils.OSUtils;
import com.codenvy.im.utils.Version;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.codenvy.im.commands.CommandLibrary.createFileBackupCommand;
import static com.codenvy.im.commands.CommandLibrary.createFileRestoreOrBackupCommand;
import static com.codenvy.im.commands.CommandLibrary.createForcePuppetAgentCommand;
import static com.codenvy.im.commands.CommandLibrary.createPackCommand;
import static com.codenvy.im.commands.CommandLibrary.createPatchCommand;
import static com.codenvy.im.commands.CommandLibrary.createPropertyReplaceCommand;
import static com.codenvy.im.commands.CommandLibrary.createStartServiceCommand;
import static com.codenvy.im.commands.CommandLibrary.createStopServiceCommand;
import static com.codenvy.im.commands.CommandLibrary.createUnpackCommand;
import static com.codenvy.im.commands.SimpleCommand.createCommand;
import static com.codenvy.im.managers.BackupConfig.Component.LDAP;
import static com.codenvy.im.managers.BackupConfig.Component.MONGO;
import static com.codenvy.im.managers.BackupConfig.getComponentTempPath;
import static java.lang.String.format;

/**
 * @author Dmytro Nochevnov
 */
public class CDECSingleServerHelper extends CDECArtifactHelper {

    public CDECSingleServerHelper(CDECArtifact original, ConfigManager configManager) {
        super(original, configManager);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getInstallInfo() throws IOException {
        return ImmutableList.of("Disable SELinux",
                                "Install puppet binaries",
                                "Unzip Codenvy binaries",
                                "Configure puppet master",
                                "Configure puppet agent",
                                "Launch puppet master",
                                "Launch puppet agent",
                                "Install Codenvy (~25 min)",
                                "Boot Codenvy");
    }

    /** {@inheritDoc} */
    @Override
    public Command getInstallCommand(Version versionToInstall, Path pathToBinaries, InstallOptions installOptions) throws IOException {
        final Config config = new Config(installOptions.getConfigProperties());
        final int step = installOptions.getStep();

        switch (step) {
            case 0:
                return new MacroCommand(ImmutableList.of(
                    createFileRestoreOrBackupCommand("/etc/selinux/config"),
                    createCommand("if sudo test -f /etc/selinux/config; then " +
                                  "    if ! grep -Fq \"SELINUX=disabled\" /etc/selinux/config; then " +
                                  "        sudo setenforce 0; " +
                                  "        sudo sed -i s/SELINUX=enforcing/SELINUX=disabled/g /etc/selinux/config; " +
                                  "        sudo sed -i s/SELINUX=permissive/SELINUX=disabled/g /etc/selinux/config; " +
                                  "    fi " +
                                  "fi ")),
                                        "Disable SELinux");

            case 1:
                return new MacroCommand(new ArrayList<Command>() {{
                    add(createCommand(
                            "if [ \"`yum list installed | grep puppetlabs-release.noarch`\" == \"\" ]; "
                            + format("then sudo yum -y -q install %s", config.getValue(Config.PUPPET_RESOURCE_URL))
                            + "; fi"));
                    add(createCommand(format("sudo yum -y -q install %s", config.getValue(Config.PUPPET_SERVER_VERSION))));
                    add(createCommand(format("sudo yum -y -q install %s", config.getValue(Config.PUPPET_AGENT_VERSION))));

                    if (OSUtils.getVersion().equals("6")) {
                        add(createCommand("sudo chkconfig --add puppetmaster"));
                        add(createCommand("sudo chkconfig puppetmaster on"));
                        add(createCommand("sudo chkconfig --add puppet"));
                        add(createCommand("sudo chkconfig puppet on"));
                    } else {
                        add(createCommand("if [ ! -f /etc/systemd/system/multi-user.target.wants/puppetmaster.service ]; then" +
                                          " sudo ln -s '/usr/lib/systemd/system/puppetmaster.service' '/etc/systemd/system/multi-user" +
                                          ".target" +
                                          ".wants/puppetmaster.service'" +
                                          "; fi"));
                        add(createCommand("sudo systemctl enable puppetmaster"));
                        add(createCommand("if [ ! -f /etc/systemd/system/multi-user.target.wants/puppet.service ]; then" +
                                          " sudo ln -s '/usr/lib/systemd/system/puppet.service' '/etc/systemd/system/multi-user.target" +
                                          ".wants/puppet.service'" +
                                          "; fi"));
                        add(createCommand("sudo systemctl enable puppet"));
                    }

                }}, "Install puppet binaries");

            case 2:
                return createCommand(format("sudo unzip -o %s -d /etc/puppet", pathToBinaries.toString()));

            case 3:
                List<Command> commands = new ArrayList<>();

                commands.add(createFileRestoreOrBackupCommand("/etc/puppet/fileserver.conf"));
                commands.add(createCommand("sudo sed -i \"\\$a[file]\"                      /etc/puppet/fileserver.conf"));
                commands.add(createCommand("sudo sed -i \"\\$a    path /etc/puppet/files\"  /etc/puppet/fileserver.conf"));
                commands.add(createCommand("sudo sed -i \"\\$a    allow *\"                 /etc/puppet/fileserver.conf"));


                commands.add(createCommand(format("sudo sed -i 's/%s/%s/g' /etc/puppet/manifests/nodes/single_server/single_server.pp",
                                                  "YOUR_DNS_NAME", config.getHostUrl())));

                for (Map.Entry<String, String> e : config.getProperties().entrySet()) {
                    String property = e.getKey();
                    String value = e.getValue();

                    commands.add(createPropertyReplaceCommand("/etc/puppet/" + Config.SINGLE_SERVER_PROPERTIES, "$" + property, value));
                    commands.add(
                        createPropertyReplaceCommand("/etc/puppet/" + Config.SINGLE_SERVER_BASE_PROPERTIES, "$" + property, value));
                }

                return new MacroCommand(commands, "Configure puppet master");

            case 4:
                return new MacroCommand(ImmutableList.of(
                    createFileRestoreOrBackupCommand("/etc/puppet/puppet.conf"),
                    createCommand("sudo sed -i '1i[master]' /etc/puppet/puppet.conf"),
                    createCommand(format("sudo sed -i '2i  certname = %s' /etc/puppet/puppet.conf", config.getHostUrl())),
                    createCommand(format("sudo sed -i 's/\\[main\\]/\\[main\\]\\n" +
                                         "  dns_alt_names = puppet,%s\\n/g' /etc/puppet/puppet.conf", config.getHostUrl())),
                    createCommand(format("sudo sed -i 's/\\[agent\\]/\\[agent\\]\\n" +
                                         "  show_diff = true\\n" +
                                         "  pluginsync = true\\n" +
                                         "  report = true\\n" +
                                         "  default_schedules = false\\n" +
                                         "  certname = %s\\n" +
                                         "  runinterval = 300\\n" +
                                         "  configtimeout = 600\\n" +
                                         "  syslogfacility = local6\\n/g' /etc/puppet/puppet.conf", config.getHostUrl()))),
                                        "Configure puppet agent");

            case 5:
                if (OSUtils.getVersion().equals("6")) {
                    return createCommand("sudo service puppetmaster start");
                } else {
                    return createCommand("sudo systemctl start puppetmaster");
                }

            case 6:
                if (OSUtils.getVersion().equals("6")) {
                    return new MacroCommand(ImmutableList.<Command>of(
                        createCommand("sleep 30"),
                        createCommand("sudo service puppet start")),
                                            "Launch puppet agent");
                } else {
                    return new MacroCommand(ImmutableList.<Command>of(
                        createCommand("sleep 30"),
                        createCommand("sudo systemctl start puppet")),
                                            "Launch puppet agent");
                }

            case 7:
                Command command = createCommand("doneState=\"Installing\"; " +
                                                "testFile=\"/home/codenvy/codenvy-tomcat/logs/catalina.out\"; " +
                                                "while [ \"${doneState}\" != \"Installed\" ]; do " +
                                                "    if sudo test -f ${testFile}; then doneState=\"Installed\"; fi; " +
                                                "    sleep 30; " +
                                                "done");
                return new PuppetErrorInterrupter(command);

            case 8:
                return new PuppetErrorInterrupter(new CheckInstalledVersionCommand(original, versionToInstall));

            default:
                throw new IllegalArgumentException(format("Step number %d is out of install range", step));
        }

    }

    /** {@inheritDoc} */
    @Override
    public Command getUpdateCommand(Version versionToUpdate, Path pathToBinaries, InstallOptions installOptions) throws IOException {
        final Config config = new Config(installOptions.getConfigProperties());
        final int step = installOptions.getStep();

        switch (step) {
            case 0:
                return createCommand(format("rm -rf /tmp/codenvy; " +
                                            "mkdir /tmp/codenvy/; " +
                                            "unzip -o %s -d /tmp/codenvy", pathToBinaries.toString()));

            case 1:
                List<Command> commands = new ArrayList<>();
                commands.add(createCommand(format("sed -i 's/%s/%s/g' /tmp/codenvy/%s",
                                                  "YOUR_DNS_NAME",
                                                  config.getHostUrl(),
                                                  Config.SINGLE_SERVER_PROPERTIES)));
                for (Map.Entry<String, String> e : config.getProperties().entrySet()) {
                    String property = e.getKey();
                    String value = e.getValue();

                    commands.add(createPropertyReplaceCommand("/tmp/codenvy/" + Config.SINGLE_SERVER_PROPERTIES, "$" + property, value));
                    commands.add(createPropertyReplaceCommand("/tmp/codenvy/" + Config.SINGLE_SERVER_BASE_PROPERTIES, "$" + property, value));
                }
                return new MacroCommand(commands, "Configure Codenvy");

            case 2:
                return createPatchCommand(Paths.get("/tmp/codenvy/patches/"),
                                          CommandLibrary.PatchType.BEFORE_UPDATE,
                                          installOptions);

            case 3:
                return createCommand("sudo rm -rf /etc/puppet/files; " +
                                     "sudo rm -rf /etc/puppet/modules; " +
                                     "sudo rm -rf /etc/puppet/manifests; " +
                                     "sudo rm -rf /etc/puppet/patches; " +
                                     "sudo mv /tmp/codenvy/* /etc/puppet");

            case 4:
                return new PuppetErrorInterrupter(new CheckInstalledVersionCommand(original, versionToUpdate));

            case 5:
                return createPatchCommand(Paths.get("/etc/puppet/patches/"),
                                          CommandLibrary.PatchType.AFTER_UPDATE,
                                          installOptions);

            default:
                throw new IllegalArgumentException(format("Step number %d is out of update range", step));
        }

    }

    /**
     * Given:
     * - path to backup file
     * - path to local backup dir for artifact
     * - codenvy config
     *
     * Commands:
     * - create temp dir
     * - stop services
     * - dump LDAP data into {backup_directory}/ldap/ldap.ldif file
     * - dump MONGO data into {backup_directory}/mongo dir
     * - pack dumps into backup file
     * - pack filesystem data into the {backup_file}/fs folder
     * - start services
     * - wait until API server starts
     * - remove temp dir
     *
     * @return MacroCommand which holds all commands
     */
    @Override
    public Command getBackupCommand(BackupConfig backupConfig) throws IOException {
        List<Command> commands = new ArrayList<>();
        Config codenvyConfig = configManager.loadInstalledCodenvyConfig();
        Path tempDir = backupConfig.obtainArtifactTempDirectory();
        Path backupFile = Paths.get(backupConfig.getBackupFile());

        // re-create local temp dir
        commands.add(createCommand(format("rm -rf %s", tempDir)));
        commands.add(createCommand(format("mkdir -p %s", tempDir)));

        // stop services
        commands.add(createStopServiceCommand("puppet"));
        commands.add(createStopServiceCommand("crond"));
        commands.add(createStopServiceCommand("codenvy"));
        commands.add(createStopServiceCommand("slapd"));

        // dump LDAP data into {backup_directory}/ldap/ldap.ldif file
        Path ldapBackupPath = getComponentTempPath(tempDir, LDAP);
        commands.add(createCommand(format("mkdir -p %s", ldapBackupPath.getParent())));
        commands.add(createCommand(format("sudo slapcat > %s", ldapBackupPath)));

        // dump MONGO data into {backup_directory}/mongo dir
        Path mongoBackupPath = getComponentTempPath(tempDir, MONGO);
        commands.add(createCommand(format("mkdir -p %s", mongoBackupPath)));
        commands.add(createCommand(format("/usr/bin/mongodump -uSuperAdmin -p%s -o %s --authenticationDatabase admin",
                                          codenvyConfig.getMongoAdminPassword(),
                                          mongoBackupPath)));

        Path adminDatabaseBackup = mongoBackupPath.resolve("admin");
        commands.add(createCommand(format("rm -rf %s", adminDatabaseBackup)));  // remove useless 'admin' database

        // pack dumps into backup file
        commands.add(createPackCommand(tempDir, backupFile, ".", false));

        // pack filesystem data into the {backup_file}/fs folder
        commands.add(createPackCommand(Paths.get("/home/codenvy/codenvy-data"), backupFile, "fs/.", true));

        // start services
        commands.add(createStartServiceCommand("puppet"));

        // wait until API server starts
        if (original.getInstalledVersion() != null) {
            commands.add(new CheckInstalledVersionCommand(original, original.getInstalledVersion()));
        }

        // remove temp dir
        commands.add(createCommand(format("rm -rf %s", tempDir)));

        return new MacroCommand(commands, "Backup data commands");
    }

    /**
     * Given:
     * - path to backup file
     * - codenvy config
     *
     * Commands:
     * - create temp dir
     * - stop services
     * - restore LDAP from {temp_backup_directory}/ldap/ladp.ldif file
     * - restore mongo from {temp_backup_directory}/mongo folder
     * - restore filesystem data from {backup_file}/fs folder
     * - start services
     * - wait until API server restarts
     * - remove temp dir
     *
     * @return MacroCommand which holds all commands
     */
    @Override
    public Command getRestoreCommand(BackupConfig backupConfig) throws IOException {
        List<Command> commands = new ArrayList<>();
        Config codenvyConfig = configManager.loadInstalledCodenvyConfig();
        Path tempDir = backupConfig.obtainArtifactTempDirectory();
        Path backupFile = Paths.get(backupConfig.getBackupFile());

        // unpack backupFile into the tempDir
        commands.add(createUnpackCommand(backupFile, tempDir));

        // stop services
        commands.add(createStopServiceCommand("puppet"));
        commands.add(createStopServiceCommand("crond"));
        commands.add(createStopServiceCommand("codenvy"));
        commands.add(createStopServiceCommand("slapd"));

        // restore LDAP from {temp_backup_directory}/ldap/ladp.ldif file
        Path ldapBackupPath = getComponentTempPath(tempDir, LDAP);
        commands.add(createCommand("sudo rm -rf /var/lib/ldap"));
        commands.add(createCommand("sudo mkdir -p /var/lib/ldap"));
        commands.add(createCommand(format("sudo slapadd -q <%s", ldapBackupPath)));
        commands.add(createCommand("sudo chown ldap:ldap /var/lib/ldap"));
        commands.add(createCommand("sudo chown ldap:ldap /var/lib/ldap/*"));


        // restore mongo from {temp_backup_directory}/mongo folder
        Path mongoBackupPath = getComponentTempPath(tempDir, MONGO);
        // remove all databases expect 'admin' one
        commands.add(createCommand(format("/usr/bin/mongo -u SuperAdmin -p %s --authenticationDatabase admin --quiet --eval " +
                                          "'db.getMongo().getDBNames().forEach(function(d){if (d!=\"admin\") db.getSiblingDB(d).dropDatabase()})'",
                                          codenvyConfig.getMongoAdminPassword())));
        commands.add(createCommand(format("/usr/bin/mongorestore -uSuperAdmin -p%s %s --authenticationDatabase admin --drop > /dev/null",  // suppress stdout to avoid hanging up SecureSSH
                                          codenvyConfig.getMongoAdminPassword(),
                                          mongoBackupPath)));

        // restore filesystem data from {backup_file}/fs folder
        commands.add(createCommand("sudo rm -rf /home/codenvy/codenvy-data/fs"));
        commands.add(CommandLibrary.createUnpackCommand(backupFile, Paths.get("/home/codenvy/codenvy-data"), "fs", true));

        // start services
        commands.add(createStartServiceCommand("puppet"));

        // wait until API server restarts
        if (original.getInstalledVersion() != null) {
            commands.add(new CheckInstalledVersionCommand(original, original.getInstalledVersion()));
        }

        // remove temp dir
        commands.add(createCommand(format("rm -rf %s", tempDir)));

        return new MacroCommand(commands, "Restore data commands");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Command getUpdateConfigCommand(Config config, Map<String, String> properties) throws IOException {
        List<Command> commands = new ArrayList<>();

        // modify codenvy single server config
        String singleServerPropertiesFilePath = configManager.getPuppetConfigFile(Config.SINGLE_SERVER_PROPERTIES).toString();
        commands.add(createFileBackupCommand(singleServerPropertiesFilePath));
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            commands.add(createPropertyReplaceCommand(singleServerPropertiesFilePath, "$" + entry.getKey(), entry.getValue()));
        }

        String singleServerBasePropertiesFilePath = configManager.getPuppetConfigFile(Config.SINGLE_SERVER_BASE_PROPERTIES).toString();
        commands.add(createFileBackupCommand(singleServerBasePropertiesFilePath));
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            commands.add(createPropertyReplaceCommand(singleServerBasePropertiesFilePath, "$" + entry.getKey(), entry.getValue()));
        }

        // force applying updated puppet config on puppet agent of API node
        commands.add(createForcePuppetAgentCommand());

        // wait until API server restarts
        if (original.getInstalledVersion() != null) {
            commands.add(new CheckInstalledVersionCommand(original, original.getInstalledVersion()));
        }

        return new MacroCommand(commands, "Change config commands");
    }
}

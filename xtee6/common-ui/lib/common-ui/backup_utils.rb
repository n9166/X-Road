require "fileutils"
require "common-ui/io_utils"
require "common-ui/script_utils"

java_import Java::java.io.RandomAccessFile
java_import Java::ee.cyber.sdsb.common.SystemProperties

# TODO: whitelist known backup files and do not allow random filenames
# from client!
module CommonUi
  module BackupUtils

    RESTORE_LOCKFILE_NAME = "restore_lock"
    RESTORE_FLAGFILE_NAME = "restore_in_progress"

    module_function

    def backup_files
      result = {}

      files = Dir.entries(SystemProperties.getConfBackupPath)

      files.each do |file|
        file_path = "#{SystemProperties.getConfBackupPath}/#{file}"

        next if File.directory?(file_path) || file.start_with?(".") ||
          !IOUtils.is_filename_valid?(file)

        result[file] = {
          :name => file,
          :size => File.size(file_path) / 1000 # We need kB
        }
      end

      result
    end

    def delete_file(filename)
      if backup_files[filename].nil?
        raise "Backup file does not exist"
      end

      if filename == nil || filename.empty?
        raise "File name must not be empty by this point!"
      end

      File.delete(backup_file(filename))
    end

    def upload_new_file(uploaded_file_param)
      filename = uploaded_file_param.original_filename
      IOUtils.validate_filename(filename)

      uploaded_backup_file = backup_file(filename)
      IOUtils.write_binary(uploaded_backup_file, uploaded_file_param.read())
    end

    def backup
      tarfile = 
        backup_file("conf_backup_#{Time.now.strftime('%Y%m%d-%H%M%S')}.tar")

      commandline = [ScriptUtils.get_script_file("backup_sdsb.sh"), tarfile]

      Rails.logger.info("Running configuration backup with command "\
                  "'#{commandline}'")

      console_output_lines = ScriptUtils.run_script(commandline)

      Rails.logger.info("Configuration backup finished with exit status" \
                  " '#{$?.exitstatus}'")
      Rails.logger.info(" --- Backup script console output - START --- ")
      Rails.logger.info("\n#{console_output_lines.join('\n')}")
      Rails.logger.info(" --- Backup script console output - END --- ")

      return $?.exitstatus, console_output_lines
    end

    def restore(conf_file, &success_handler)
      if backup_files[conf_file].nil?
        raise "Backup file does not exist"
      end

      commandline = [
        ScriptUtils.get_script_file("restore_sdsb.sh"), backup_file(conf_file) ]

      lockfile = try_restore_lock

      unless lockfile
        Rails.logger.info("Aborting restore, another restore already in progress")
        raise "Restore already in progress"
      end

      FileUtils.touch(IOUtils.temp_file(RESTORE_FLAGFILE_NAME))

      Rails.logger.info("Running configuration restore with command "\
                  "'#{commandline.join(" ")}'")

      console_output_lines = ScriptUtils.run_script(commandline)

      Rails.logger.info("Restoring configuration finished with exit status" \
                  " '#{$?.exitstatus}'")
      Rails.logger.info(" --- Restore script console output - START --- ")
      Rails.logger.info("\n#{console_output_lines.join('\n')}")
      Rails.logger.info(" --- Restore script console output - END --- ")

      return $?.exitstatus, console_output_lines
    ensure
      begin
        yield if success_handler
      ensure
        if lockfile
          FileUtils.rm_f(IOUtils.temp_file(RESTORE_FLAGFILE_NAME)) # shouldn't throw?
          release_restore_lock(lockfile)
        end
      end
    end

    def try_restore_lock
      lockfile = RandomAccessFile.new(IOUtils.temp_file(RESTORE_LOCKFILE_NAME), "rw")
      lockfile.getChannel.tryLock && lockfile
    end

    def release_restore_lock(lockfile)
      # closing lockfile releases the lock
      lockfile.close
    end

    def restore_in_progress?
      File.exists?(IOUtils.temp_file(RESTORE_FLAGFILE_NAME))
    end

    def backup_file(filename)
      "#{SystemProperties.getConfBackupPath}/#{filename}"
    end
  end
end

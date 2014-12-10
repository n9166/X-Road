require 'thread'
require 'fileutils'

java_import Java::java.lang.System

# Common file read/write functions
# TODO Remove Mutex. Acquire file locks for concrete files. RM #3505.
module CommonUi
  module IOUtils

    @@mutex = Mutex.new

    module_function

    # Writes in threadsafe manner reasonably small text file using encoding
    # specified by the application configuration.
    def write(file_path, content)
      Rails.logger.debug("write(#{file_path}, #{content})")

      @@mutex.synchronize do
        File.open(file_path, "w:#{Rails.configuration.encoding}") do |f|
          f.write(content)
        end
      end
    end

    # Writes in threadsafe manner reasonably small binary files.
    def write_binary(file_path, content)
      Rails.logger.debug("write_binary(#{file_path}, #{content})")

      @@mutex.synchronize do
        File.open(file_path, 'wb') {|f| f.write(content) }
      end
    end

    # Writes in threadsafe manner text file intended to be shared to the World.
    # Second parameter is Proc object that does actual writing, as public files
    # may be large in size.
    def write_public(file_path, writing_process)
      @@mutex.synchronize do
        begin
          file = File.open(file_path, "w:#{Rails.configuration.encoding}")
          writing_process.call(file)
          FileUtils.chmod(0644, file.path)
        ensure
          file.close()
        end
      end
    end

    def read(file_path)
      result = ""

      @@mutex.synchronize do
        File.open(file_path, "r:#{Rails.configuration.encoding}") do |f|
          result << f.read()
        end
      end

      return result
    end

    def read_binary(file_path)
      @@mutex.synchronize do
        File.open(file_path, "rb") do |f|
          return f.read()
        end
      end
    end

    def read_to_array(file_path)
      return [] if !(File.exist?(file_path))
      result = []

      @@mutex.synchronize do
        File.open(file_path, "r:#{Rails.configuration.encoding}") do |f|
          f.each_line do |each|
            result << each
          end
        end
      end

      return result
    end

    def get_log_dir
      return System.getProperty("ee.cyber.sdsb.appLog.path", "/var/log/sdsb")
    end

    # Returns the full path to a file in temp dir.
    def temp_file(file)
      temp_dir = SystemProperties::getTempFilesPath

      unless File.directory?(temp_dir)
        FileUtils.mkdir_p(temp_dir)
      end

      "#{temp_dir}/#{file}"
    end

    def validate_filename(filename)
      if !is_filename_valid?(filename)
        raise I18n.t("common.filename_error", :file => filename)
      end
    end

    def is_filename_valid?(filename)
      return filename =~ /\A[\w\.\-]+\z/
    end
  end
end

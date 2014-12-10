# XXX: This is actually work-around to make 'rake test:controllers' work as
# expected. For some reason, it doesn't work by default.
namespace :test do
  task :controllers do
    sdsb_home = ENV["SDSB_HOME"]
    if !sdsb_home || sdsb_home.empty?
      raise "Environment variable SDSB_HOME must be set to project root dir!"
    end

    system("rake db:test:prepare")

    controller_tests_home = "#{sdsb_home}/center-service/test/controllers"
    Dir.new(controller_tests_home).each do |file|
      if file.end_with?("_test.rb")
        test_file_path = "#{controller_tests_home}/#{file}"
        system("jruby -I test #{test_file_path}")
        if $?.exitstatus != 0
          raise "There were failed controller tests, check log above"
        end
      end
    end
  end
end

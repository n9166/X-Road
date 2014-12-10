namespace :localtest do
  # For some reason loading these ones separately here is needed.
  DEPENDENCIES = ["build/libs/*.jar", "../common-ui/lib/**/*.rb"]

  DEPENDENCIES.each do |each_dep|
    Dir[File.expand_path(each_dep)].each do |each_file|
      require(each_file)
    end
  end

  @test_data_file = './test/datagen/test_data.rb'

  def test_data_present?
    File.exist?(@test_data_file)
  end

  if test_data_present?()
    require @test_data_file
  end

  # Tested currently only on Postgres database.
  def cleanup_database
    puts "Starting database cleanup"

    connection = ActiveRecord::Base.connection
    connection.tables.collect do |table_name|
      # Let us preserve migrations.
      next if table_name == "schema_migrations"

      puts "Truncating table '#{table_name}' ..."
      connection.execute("TRUNCATE #{table_name} CASCADE")
    end

    puts "Completed database cleanup"
  end

  def create_test_data
    puts "Starting creation of test data"

    ActiveRecord::Base.transaction do
      create_all_test_data()
    end

    puts "Completed creation of test data"
  end

  task :create_test_data => :environment do
    if test_data_present?()
      cleanup_database()
      create_test_data()
    else
      puts "Test data not present, database remains unchanged."
    end
  end
end

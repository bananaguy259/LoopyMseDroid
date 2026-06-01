/**
 * options.cpp (Android version)
 *
 * The desktop version uses Boost program_options to parse the INI and argv.
 * On Android we have no command-line and no Boost, so this file just delegates
 * to IniParser:: for the INI parsing.
 *
 * The Options::Args struct and namespace are defined in android/ini_parser.h,
 * which is included wherever options.h was included on desktop.
 */

#include "../android/ini_parser.h"
#include <log/log.h>

// Re-export under the Options:: namespace that main.cpp uses on desktop
namespace Options
{

bool parse_config(const std::string& path, Args& args)
{
    return IniParser::parse_config(path, args);
}

void parse_commandline(int argc, char** argv, Args& args)
{
    IniParser::parse_commandline(argc, argv, args);
}

void print_usage()
{
    IniParser::print_usage();
}

} // namespace Options

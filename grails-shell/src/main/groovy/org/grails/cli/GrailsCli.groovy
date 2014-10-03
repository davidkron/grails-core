package org.grails.cli

import grails.build.logging.GrailsConsole
import grails.config.GrailsConfig
import grails.io.SystemStreamsRedirector
import grails.util.Environment
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import jline.console.completer.AggregateCompleter

import org.codehaus.groovy.grails.cli.parsing.CommandLine
import org.codehaus.groovy.grails.cli.parsing.CommandLineParser
import org.grails.cli.gradle.GradleConnectionCommandLineHandler
import org.grails.cli.profile.CommandDescription
import org.grails.cli.profile.CommandLineHandler
import org.grails.cli.profile.ExecutionContext
import org.grails.cli.profile.Profile
import org.grails.cli.profile.ProfileRepository
import org.grails.cli.profile.ProjectContext
import org.yaml.snakeyaml.Yaml

@CompileStatic
class GrailsCli {
    public static final String DEFAULT_PROFILE_NAME = 'web'
    private final SystemStreamsRedirector originalStreams = SystemStreamsRedirector.original() // store original System.in, System.out and System.err
    List<CommandLineHandler> commandLineHandlers=[]
    AggregateCompleter aggregateCompleter=new AggregateCompleter()
    CommandLineParser cliParser = new CommandLineParser()
    boolean keepRunning = true
    Boolean ansiEnabled = null
    Character defaultInputMask = null
    ProfileRepository profileRepository=new ProfileRepository()
    GrailsConfig applicationConfig
    ProjectContext projectContext
    
    public int execute(String... args) {
        CommandLine mainCommandLine=cliParser.parse(args)
        if(mainCommandLine.hasOption("verbose")) {
            System.setProperty("grails.verbose", "true")
        }
        if(mainCommandLine.hasOption("stacktrace")) {
            System.setProperty("grails.show.stacktrace", "true")
        }
        
        File grailsAppDir=new File("grails-app")
        if(!grailsAppDir.isDirectory()) {
            if(!mainCommandLine || !mainCommandLine.commandName || mainCommandLine.commandName != 'create-app' || !mainCommandLine.getRemainingArgs()) {
                System.err.println "usage: create-app appname --profile=web"
                return 1
            }
            return createApp(mainCommandLine, profileRepository)
        } else {
            applicationConfig = loadApplicationConfig()
            initializeProfile()
        
            def commandName = mainCommandLine.getCommandName()
            GrailsConsole console=GrailsConsole.getInstance()
            console.setAnsiEnabled(!mainCommandLine.hasOption(CommandLine.NOANSI_ARGUMENT))
            console.defaultInputMask = defaultInputMask
            if(ansiEnabled != null) {
                console.setAnsiEnabled(ansiEnabled)
            }
            File baseDir = new File("").absoluteFile
            projectContext = new ProjectContextImpl(console, baseDir, applicationConfig)
            if(commandName) {
                handleCommand(args.join(" "), mainCommandLine)
            } else {
                handleInteractiveMode()
            }
        }
        return 0
    }

    private handleInteractiveMode() {
        System.setProperty(Environment.INTERACTIVE_MODE_ENABLED, "true")
        GrailsConsole console = projectContext.console
        console.reader.addCompleter(aggregateCompleter)
        console.println("Starting interactive mode...")
        while(keepRunning) {
            try {
                String commandLine = console.showPrompt()
                if(commandLine==null) {
                    // CTRL-D was pressed, exit interactive mode
                    exitInteractiveMode()
                } else {
                    handleCommand(commandLine, cliParser.parseString(commandLine))
                }
            } catch (Exception e) {
                console.error "Caught exception ${e.message}", e
            }
        }
    }

    private initializeProfile() {
        String profileName = applicationConfig.navigateConfig('grails', 'profile') ?: DEFAULT_PROFILE_NAME
        Profile profile = profileRepository.getProfile(profileName)
        commandLineHandlers.addAll(profile.getCommandLineHandlers(projectContext) as Collection)
        commandLineHandlers.add(new GradleConnectionCommandLineHandler())
        aggregateCompleter.getCompleters().addAll((profile.getCompleters(projectContext)?:[]) as Collection)
    }
    
    private GrailsConfig loadApplicationConfig() {
        GrailsConfig config = new GrailsConfig()
        File applicationYml = new File("grails-app/conf/application.yml")
        if(applicationYml.exists()) {
            config.loadYml(applicationYml)
        }
        config
    }
    
    private int createApp(CommandLine mainCommandLine, ProfileRepository profileRepository) {
        String groupAndAppName = mainCommandLine.getRemainingArgs()[0]
        String profileName = mainCommandLine.optionValue('profile')
        if(!profileName) {
            profileName=DEFAULT_PROFILE_NAME
        }
        Profile profile = profileRepository.getProfile(profileName)
        if(profile) {
            CreateAppCommand cmd = new CreateAppCommand(profileRepository: profileRepository, groupAndAppName: groupAndAppName, profile: profileName)
            cmd.run()
            return 0
        } else {
            System.err.println "Cannot find profile $profileName"
            return 1
        }
    }
    
    boolean handleCommand(String unparsedCommandLine, CommandLine commandLine) {
        ExecutionContext context = new ExecutionContextImpl(unparsedCommandLine, commandLine, projectContext)
        
        if(handleBuiltInCommands(context)) {
            return true
        }
        for(CommandLineHandler handler : commandLineHandlers) {
             if(handler.handleCommand(context)) {
                 return true
             }
        }
        context.console.error("Command not found ${commandLine.commandName}")
        return false
    }

    private boolean handleBuiltInCommands(ExecutionContext context) {
        CommandLine commandLine = context.commandLine
        GrailsConsole console = context.console
        switch(commandLine.getCommandName()) {
            case 'help':
                List<CommandDescription> allCommands=findAllCommands()
                String remainingArgs = commandLine.getRemainingArgsString()
                if(remainingArgs?.trim()) {
                    CommandLine remainingArgsCommand = cliParser.parseString(remainingArgs)
                    String helpCommandName = remainingArgsCommand.getCommandName()
                    for (CommandDescription desc : allCommands) {
                        if(desc.name == helpCommandName) {
                            console.println "${desc.name}\t${desc.description}\n${desc.usage}"
                            return true
                        }
                    }
                    console.error "Help for command $helpCommandName not found"
                    return false
                } else {
                    for (CommandDescription desc : allCommands) {
                        console.println "${desc.name}\t${desc.description}"
                    }
                    console.println("detailed usage with help [command]")
                    return true
                }
                break
            case 'exit':
                exitInteractiveMode()
                return true
                break
        }
        return false
    }
    
    private void exitInteractiveMode() {
        keepRunning = false
    }

    private List<CommandDescription> findAllCommands() {
        List<CommandDescription> allCommands=[]
        for(CommandLineHandler handler : commandLineHandlers) {
            allCommands.addAll((handler.listCommands(projectContext) ?: []) as Collection)
        }
        allCommands = allCommands.sort(false) { CommandDescription desc ->
            desc.getName()
        }
        allCommands
    }
    
    public static void main(String[] args) {
        GrailsCli cli=new GrailsCli()
        System.exit(cli.execute(args))
    }
    
    
    @Canonical
    private static class ExecutionContextImpl implements ExecutionContext {
        String unparsedCommandLine
        CommandLine commandLine
        @Delegate ProjectContext projectContext
    }
    
    @Canonical
    private static class ProjectContextImpl implements ProjectContext {
        GrailsConsole console
        File baseDir
        GrailsConfig grailsConfig

        @Override
        public String navigateConfig(String... path) {
            grailsConfig.navigateConfig(path)
        }

        @Override
        public <T> T navigateConfigForType(Class<T> requiredType, String... path) {
            grailsConfig.navigateConfigForType(requiredType, path)
        }        
    }
}

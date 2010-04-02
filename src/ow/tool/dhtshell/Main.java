/*
 * Copyright 2006-2009 National Institute of Advanced Industrial Science
 * and Technology (AIST), and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ow.tool.dhtshell;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.dhtfox.DHTFox;
import org.slf4j.bridge.SLF4JBridgeHandler;

import ow.dht.DHT;
import ow.dht.DHTConfiguration;
import ow.dht.DHTFactory;
import ow.messaging.util.AccessController;
import ow.tool.dhtshell.commands.ClearCommand;
import ow.tool.dhtshell.commands.GetCommand;
import ow.tool.dhtshell.commands.HaltCommand;
import ow.tool.dhtshell.commands.HelpCommand;
import ow.tool.dhtshell.commands.InitCommand;
import ow.tool.dhtshell.commands.LocaldataCommand;
import ow.tool.dhtshell.commands.PutCommand;
import ow.tool.dhtshell.commands.QuitCommand;
import ow.tool.dhtshell.commands.RemoveCommand;
import ow.tool.dhtshell.commands.ResumeCommand;
import ow.tool.dhtshell.commands.SetSecretCommand;
import ow.tool.dhtshell.commands.SetTTLCommand;
import ow.tool.dhtshell.commands.StatusCommand;
import ow.tool.dhtshell.commands.SuspendCommand;
import ow.tool.util.shellframework.Command;
import ow.tool.util.shellframework.Interruptible;
import ow.tool.util.shellframework.MessagePrinter;
import ow.tool.util.shellframework.Shell;
import ow.tool.util.shellframework.ShellServer;
import ow.tool.util.toolframework.AbstractDHTBasedTool;

/**
 * The main class of DHT shell server.
 * This shell is an utility to use/test a DHT.
 */
public final class Main extends AbstractDHTBasedTool<String>
        implements Interruptible {

    private final static String COMMAND = "owdhtshell";	// A shell/batch script provided as bin/owdhtshell
    private final static int SHELL_PORT = -1;
    public final static int XMLRPC_PORT_DIFF = +1;
    private final static int XMLRPC_PORT_RANGE = 100;
    public final static String ENCODING = "UTF-8";
    private final static Class/*Command<<DHT<String>>>*/[] COMMANDS = {
        StatusCommand.class,
        InitCommand.class,
        GetCommand.class, PutCommand.class, RemoveCommand.class,
        SetTTLCommand.class, SetSecretCommand.class,
        LocaldataCommand.class,
        //		SourceCommand.class,
        HelpCommand.class,
        QuitCommand.class,
        HaltCommand.class,
        ClearCommand.class,
        SuspendCommand.class, ResumeCommand.class
    };
    private final static List<Command<DHT<String>>> commandList;
    private final static Map<String, Command<DHT<String>>> commandTable;

    static {
        commandList = ShellServer.createCommandList(COMMANDS);
        commandTable = ShellServer.createCommandTable(commandList);
    }
    private Thread mainThread = null;

    protected void usage(String command) {
        super.usage(command, "[-p <shell port>] [--acl <ACL file>] [-n] [--upnp]");
    }

    public static void main(String[] args) {
        SLF4JBridgeHandler.install();
        (new Main()).start(args);
    }

    protected void start(String[] args) {
        Shell<DHT<String>> stdioShell = null;

        stdioShell = this.init(args, System.in, System.out, true);

        if (stdioShell != null) {
            stdioShell.run();	// this call is blocked
        }
    }

    /**
     * Implements {@link EmulatorControllable#invoke(int, String[], PrintStream)
     * EmulatorControllable#invoke}.
     */
    public Writer invoke(String[] args, PrintStream out) {
        Shell<DHT<String>> stdioShell = this.init(args, null, out, false);

        if (stdioShell != null) {
            return stdioShell.getWriter();
        } else {
            return null;
        }
    }

    private Shell<DHT<String>> init(String[] args, InputStream in, PrintStream out, boolean interactive) {
        int shellPort = SHELL_PORT;
        AccessController ac = null;
        boolean disableStdin = false, enableUPnP = false;

        this.mainThread = Thread.currentThread();

        // parse command-line arguments
        Options opts = this.getInitialOptions();
        opts.addOption("p", "port", true, "port number");
        opts.addOption("A", "acl", true, "access control list file");
        opts.addOption("n", "disablestdin", false, "disable standard input");
        opts.addOption("u", "upnp", false, "enable UPnP");

        CommandLineParser parser = new PosixParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(opts, args);
        } catch (ParseException e) {
            System.out.println("There is an invalid option.");
            e.printStackTrace();
            System.exit(1);
        }

        parser = null;
        opts = null;

        String optVal;
        optVal = cmd.getOptionValue('p');
        if (optVal != null) {
            shellPort = Integer.parseInt(optVal);
        }
        optVal = cmd.getOptionValue("A");
        if (optVal != null) {
            try {
                ac = new AccessController(optVal);
            } catch (IOException e) {
                System.err.println("An Exception thrown:");
                e.printStackTrace();
                return null;
            }
        }
        if (cmd.hasOption('n')) {
            disableStdin = true;
        }
        if (cmd.hasOption('u')) {
            enableUPnP = true;
        }

        // parse remaining arguments
        // and initialize a DHT
        DHT<String> dht = null;
        DHTConfiguration config = DHTFactory.getDefaultConfiguration();
        /*
        config.setImplementationName("ChurnTolerantDHT");
        config.setDirectoryType("PersistentMap");
        config.setMessagingTransport("UDP");
        config.setRoutingAlgorithm("Kademlia");
        config.setRoutingStyle("Iterative");
        config.setDoExpire(true);
        config.setDoReputOnRequester(false);
        config.setUseTimerInsteadOfThread(false);
         */
        config.setDoUPnPNATTraversal(enableUPnP);
        try {
            dht = super.initialize(DHTFox.APPLICATION_ID, DHTFox.APPLICATION_MAJOR_VERSION,
                    config,
                    COMMAND, cmd);
        } catch (Exception e) {
            System.err.println("An Exception thrown:");
            e.printStackTrace();
            return null;
        }

        cmd = null;

        // start a ShellServer
        ShellServer<DHT<String>> shellServ =
                new ShellServer<DHT<String>>(commandTable, commandList,
                new ShowPromptPrinter(), new NoCommandPrinter(), null,
                dht, shellPort, ac);
        shellServ.addInterruptible(this);

        Shell<DHT<String>> stdioShell = null;
        if (disableStdin) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        } else {
            stdioShell = new Shell<DHT<String>>(in, out, shellServ, dht, interactive);
        }

        return stdioShell;
    }

    public void interrupt() {
        if (this.mainThread != null && !this.mainThread.equals(Thread.currentThread())) {
            this.mainThread.interrupt();
        }
    }

    private static class ShowPromptPrinter implements MessagePrinter {

        public void execute(PrintStream out, String hint) {
            out.print("Ready." + Shell.CRLF);
            out.flush();
        }
    }

    private static class NoCommandPrinter implements MessagePrinter {

        public void execute(PrintStream out, String hint) {
            out.print("No such command");

            if (hint != null) {
                out.print(": " + hint);
            } else {
                out.print(".");
            }
            out.print(Shell.CRLF);

            out.flush();
        }
    }
}

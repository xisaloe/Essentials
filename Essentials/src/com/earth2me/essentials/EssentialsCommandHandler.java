package com.earth2me.essentials;

import static com.earth2me.essentials.I18n._;
import com.earth2me.essentials.api.*;
import com.earth2me.essentials.commands.EssentialsCommand;
import com.earth2me.essentials.commands.IEssentialsCommand;
import com.earth2me.essentials.commands.NoChargeException;
import com.earth2me.essentials.commands.NotEnoughArgumentsException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginCommandYamlParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;


public class EssentialsCommandHandler implements ICommandHandler
{
	private final transient ClassLoader classLoader;
	private final transient String commandPath;
	private final transient String permissionPrefix;
	private final transient IEssentialsModule module;
	private static final transient Logger LOGGER = Bukkit.getLogger();
	private final transient Map<String, List<PluginCommand>> altcommands = new HashMap<String, List<PluginCommand>>();
	private final transient Map<String, String> disabledList = new HashMap<String, String>();
	private final transient Map<String, IEssentialsCommand> commands = new HashMap<String, IEssentialsCommand>();
	private final transient IEssentials ess;

	public EssentialsCommandHandler(ClassLoader classLoader, String commandPath, String permissionPrefix, IEssentials ess)
	{
		this(classLoader, commandPath, permissionPrefix, null, ess);
	}

	public EssentialsCommandHandler(ClassLoader classLoader, String commandPath, String permissionPrefix, IEssentialsModule module, IEssentials ess)
	{
		this.classLoader = classLoader;
		this.commandPath = commandPath;
		this.permissionPrefix = permissionPrefix;
		this.module = module;
		this.ess = ess;
		for (Plugin plugin : ess.getServer().getPluginManager().getPlugins())
		{
			if (plugin.isEnabled())
			{
				addPlugin(plugin);
			}
		}
	}

	@Override
	public boolean handleCommand(final CommandSender sender, final Command command, final String commandLabel, final String[] args)
	{
		boolean disabled = false;
		boolean overridden = false;
		ISettings settings = ess.getSettings();
		settings.acquireReadLock();
		try
		{
			disabled = settings.getData().getCommands().isDisabled(command.getName());
			overridden = !disabled || settings.getData().getCommands().isOverridden(command.getName());
		}
		finally
		{
			settings.unlock();
		}
		// Allow plugins to override the command via onCommand
		if (!overridden && (!commandLabel.startsWith("e") || commandLabel.equalsIgnoreCase(command.getName())))
		{
			final PluginCommand pc = getAlternative(commandLabel);
			if (pc != null)
			{
				executed(commandLabel, pc.getLabel());
				return pc.execute(sender, commandLabel, args);
			}
		}

		try
		{
			IUser user = null;
			if (sender instanceof Player)
			{
				user = ess.getUser((Player)sender);
				LOGGER.log(Level.INFO, String.format("[PLAYER_COMMAND] %s: /%s %s ", ((Player)sender).getName(), commandLabel, EssentialsCommand.getFinalArg(args, 0)));
			}

			// Check for disabled commands
			if (disabled)
			{
				return true;
			}

			final String commandName = command.getName().toLowerCase(Locale.ENGLISH);
			IEssentialsCommand cmd = commands.get(commandName);
			if (cmd == null)
			{
				try
				{
					cmd = (IEssentialsCommand)classLoader.loadClass(commandPath + commandName).newInstance();
					cmd.init(ess, commandName);
					cmd.setEssentialsModule(module);
					commands.put(commandName, cmd);
				}
				catch (Exception ex)
				{
					sender.sendMessage(_("commandNotLoaded", commandName));
					LOGGER.log(Level.SEVERE, _("commandNotLoaded", commandName), ex);
					return true;
				}
			}

			// Check authorization
			if (user != null && !user.isAuthorized(cmd))
			{
				LOGGER.log(Level.WARNING, _("deniedAccessCommand", user.getName()));
				user.sendMessage(_("noAccessCommand"));
				return true;
			}

			// Run the command
			try
			{
				if (user == null)
				{
					cmd.run(sender, command, args);
				}
				else
				{
					user.acquireReadLock();
					try
					{
						cmd.run(user, command, args);
					}
					finally
					{
						user.unlock();
					}
				}
				return true;
			}
			catch (NoChargeException ex)
			{
				return true;
			}
			catch (NotEnoughArgumentsException ex)
			{
				sender.sendMessage(command.getDescription());
				sender.sendMessage(command.getUsage().replaceAll("<command>", commandLabel));
				if (!ex.getMessage().isEmpty())
				{
					sender.sendMessage(ex.getMessage());
				}
				return true;
			}
			catch (Throwable ex)
			{
				showCommandError(sender, commandLabel, ex);
				return true;
			}
		}
		catch (Throwable ex)
		{
			LOGGER.log(Level.SEVERE, _("commandFailed", commandLabel), ex);
			return true;
		}
	}

	@Override
	public void showCommandError(final CommandSender sender, final String commandLabel, final Throwable exception)
	{
		sender.sendMessage(_("errorWithMessage", exception.getMessage()));
		if (ess.getSettings().isDebug())
		{
			LOGGER.log(Level.WARNING, _("errorCallingCommand", commandLabel), exception);
		}
	}

	@Override
	public void onReload()
	{
	}

	public final void addPlugin(final Plugin plugin)
	{
		if (plugin.getDescription().getMain().contains("com.earth2me.essentials"))
		{
			return;
		}
		final List<Command> commands = PluginCommandYamlParser.parse(plugin);
		final String pluginName = plugin.getDescription().getName().toLowerCase(Locale.ENGLISH);

		for (Command command : commands)
		{
			final PluginCommand pc = (PluginCommand)command;
			final List<String> labels = new ArrayList<String>(pc.getAliases());
			labels.add(pc.getName());

			PluginCommand reg = ess.getServer().getPluginCommand(pluginName + ":" + pc.getName().toLowerCase(Locale.ENGLISH));
			if (reg == null)
			{
				reg = ess.getServer().getPluginCommand(pc.getName().toLowerCase(Locale.ENGLISH));
			}
			if (reg == null || !reg.getPlugin().equals(plugin))
			{
				continue;
			}
			for (String label : labels)
			{
				List<PluginCommand> plugincommands = altcommands.get(label.toLowerCase(Locale.ENGLISH));
				if (plugincommands == null)
				{
					plugincommands = new ArrayList<PluginCommand>();
					altcommands.put(label.toLowerCase(Locale.ENGLISH), plugincommands);
				}
				boolean found = false;
				for (PluginCommand pc2 : plugincommands)
				{
					if (pc2.getPlugin().equals(plugin))
					{
						found = true;
					}
				}
				if (!found)
				{
					plugincommands.add(reg);
				}
			}
		}
	}

	public void removePlugin(final Plugin plugin)
	{
		final Iterator<Map.Entry<String, List<PluginCommand>>> iterator = altcommands.entrySet().iterator();
		while (iterator.hasNext())
		{
			final Map.Entry<String, List<PluginCommand>> entry = iterator.next();
			final Iterator<PluginCommand> pcIterator = entry.getValue().iterator();
			while (pcIterator.hasNext())
			{
				final PluginCommand pc = pcIterator.next();
				if (pc.getPlugin() == null || pc.getPlugin().equals(plugin))
				{
					pcIterator.remove();
				}
			}
			if (entry.getValue().isEmpty())
			{
				iterator.remove();
			}
		}
	}

	public PluginCommand getAlternative(final String label)
	{
		final List<PluginCommand> commands = altcommands.get(label);
		if (commands == null || commands.isEmpty())
		{
			return null;
		}
		if (commands.size() == 1)
		{
			return commands.get(0);
		}
		// return the first command that is not an alias
		for (PluginCommand command : commands)
		{
			if (command.getName().equalsIgnoreCase(label))
			{
				return command;
			}
		}
		// return the first alias
		return commands.get(0);
	}

	public void executed(final String label, final String otherLabel)
	{
		if (ess.getSettings().isDebug())
		{
			LOGGER.log(Level.INFO, "Essentials: Alternative command " + label + " found, using " + otherLabel);
		}
		disabledList.put(label, otherLabel);
	}

	public Map<String, String> disabledCommands()
	{
		return disabledList;
	}
}

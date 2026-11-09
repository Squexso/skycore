package dev.skycore.share;

import java.util.UUID;

/** A message SKYCORE prepared for the player. It is only ever copied on request; nothing is sent. */
public class Prepared {
    public String id = UUID.randomUUID().toString();
    public long created = System.currentTimeMillis();
    /** Routing category ("catacombs", "goal_checkpoint" ...). */
    public String category = "";
    public String kind = "completed";
    public String stat = "";
    /** Short headline for the inbox ("Catacombs 40"). */
    public String title = "";
    /** The message as it will be pasted (without the chat command prefix). */
    public String text = "";
    public String destination = "party";
    /** True if the message contains a value that is only an estimate (marked with "~"). */
    public boolean estimated;

    public Destination destination() { return Destination.parse(destination, Destination.MANUAL); }

    /** What goes on the clipboard for a destination: the command prefix the player confirms, then the text. */
    public String clipboardText(Destination d) { return d.prefix + text; }
}

Copy the TgAuth plugin jar (built from the TgAuth sources that contain API version 4) here and name it:

    TgAuth.jar

Then run:  mvn package
The addon jar appears in target/DiscordTgAuth-addon-1.0.0.jar. The TgAuth jar is only used to compile the addon and is not
bundled; JDA (the Discord library) is not inside it either: Paper downloads it from Maven Central on first start.

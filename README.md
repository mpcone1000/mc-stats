# MC-Stats

Meldet sich alle 15 Minuten fuer hoechstens 10 Sekunden auf einem Minecraft-Server an, fragt `/bal` und `/topgeld` ab und geht wieder.
Die Daten liegen verschluesselt in `docs/daten.enc` und werden auf der Seite (GitHub Pages) mit dem Schluessel aus dem persoenlichen Link entschluesselt.
Serveradresse und Schluessel stehen nur in den Repository-Geheimnissen.

Sicherheit: kein Join wenn der Server aus ist oder du selbst online bist; sofort raus bei Schaden, wenig Leben oder Tod;
bei Monstern/Spielern in der Naehe nur ganz kurz `/bal`; harte Grenze 10 Sekunden; der Bot bewegt sich nie.

Anhalten: GitHub -> Actions -> Abfrage -> "Disable workflow".

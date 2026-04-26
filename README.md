# Lammclones

Application desktop pour rechercher et nettoyer les **fichiers en doublon** sur un disque (cas principal : disques durs externes).

## Fonctionnalités

- **Multi-sources** : ajout de plusieurs racines à scanner (intra-disque ou inter-disques branchés simultanément), dédoublonnage automatique des paths qui se chevauchent
- **Algorithme 3 phases** pensé pour les I/O lents (USB / SATA externe) :
  1. Groupage par taille (`File.length()`) — élimine la majorité sans I/O lourd
  2. Hash partiel SHA-256 sur les premiers 64 KB des candidats
  3. Hash complet SHA-256 sur les survivants pour confirmer
- **Vue arbre** des doublons avec checkboxes par fichier, icônes folder/file Lamm
- **Pré-sélection intelligente** :
  - Tout sauf le plus ancien (mtime)
  - Tout sauf le plus récent (mtime)
  - Tout sauf celui sur un disque source donné (utile pour consolider sur un disque)
- **Suppression vers corbeille** (jamais d'effacement direct) : `Desktop.moveToTrash` en premier, fallback `gio trash` sous Linux/GNOME où l'API AWT échoue à détecter le support corbeille
- **UI JavaFX** : Stage `UNDECORATED` + chrome Lamm (drag, min/max/close, cog réglages avec light/dark, presets d'accent, configuration, à propos)
- Annulation à chaud du scan, progression live (parcours indéterminé puis fraction 50/50 partial/full)

## Stack

- Java 25
- JavaFX + [LammUI 2.0+](https://github.com/Jeremy27/LammUI)
- Stage `UNDECORATED` avec chrome custom (`LammChromeFx`)
- Maven + shade (jar uber)

## Prérequis

- Java 25+
- Maven 3.9+
- Accès à GitHub Packages pour tirer LammUI (cf. section ci-dessous)
- Sous Linux : `gio` (paquet `glib2`) recommandé pour le déplacement vers la corbeille — `Desktop.moveToTrash` retourne souvent `false` dans une app JavaFX-only sous Wayland

## Accès à LammUI depuis GitHub Packages

LammUI est publiée sur `https://maven.pkg.github.com/Jeremy27/LammUI`. Maven a besoin d'un token pour la télécharger. Dans `~/.m2/settings.xml` :

```xml
<servers>
  <server>
    <id>github-lammui</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_PAT_WITH_READ_PACKAGES</password>
  </server>
</servers>
```

## Build

```bash
mvn package
```

## Lancement

```bash
java -jar target/lammclones-X.Y.Z.jar
```

Ou depuis l'IDE : pointer la run config sur `fr.courel.lammclones.Main` (et **pas** sur `App` — JavaFX 11+ refuse de démarrer une `Application` hors module-path).

## Algorithme de scan (détail)

Le scan ne hashe **jamais** tout le disque en brute force. Pour un disque externe contenant des dizaines de milliers de fichiers, l'économie est majeure :

1. **Phase 1** — `Files.walk` sur chaque racine, on groupe par `File.length()`. Pas de hash, juste un `stat`. Les fichiers uniques en taille sortent du jeu.
2. **Phase 2** — pour chaque groupe ≥ 2, on calcule un SHA-256 sur les 64 premiers Ko. Élimine les faux positifs (fichiers de même taille mais début différent).
3. **Phase 3** — pour chaque groupe encore ≥ 2 après phase 2, on hashe le fichier entier. Confirme l'égalité bit à bit.

Les fichiers vides (0 octet) et les symlinks sont ignorés.

## Structure

```
src/main/java/fr/courel/lammclones/
├── scan/       # DuplicateScanner, ScanListener, ScanResult, DuplicateGroup
├── io/         # Trasher (Desktop.moveToTrash + fallback gio trash)
├── App.java    # UI JavaFX (chrome, sources, scan, résultats, suppression)
└── Main.java   # launcher
```

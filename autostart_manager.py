import os
import subprocess
import logging

import config

logger = logging.getLogger("TankRadar.Autostart")

class AutostartManager:
    def __init__(self):
        self.shortcut_name = "TankRadar.lnk"
        # Anchor on the installation directory instead of the current working
        # directory: the shortcut is written once but TankRadar can be started
        # from anywhere, which previously produced a shortcut to a missing file.
        self.target_path = str(config.BASE_DIR / "start_tankradar.bat")
        self.startup_folder = self._get_startup_folder()
        self.shortcut_path = (
            os.path.join(self.startup_folder, self.shortcut_name)
            if self.startup_folder
            else None
        )

    @staticmethod
    def _get_startup_folder():
        """Return the Windows startup folder, or None on unsupported systems."""
        appdata = os.environ.get('APPDATA')
        if not appdata:
            return None
        return os.path.join(appdata, r"Microsoft\Windows\Start Menu\Programs\Startup")

    def is_enabled(self):
        return bool(self.shortcut_path and os.path.exists(self.shortcut_path))

    def set_autostart(self, enable: bool):
        if not self.shortcut_path:
            logger.warning("Autostart is only available on Windows with APPDATA set.")
            return False

        if enable:
            if self.is_enabled():
                return True
            
            try:
                # Use PowerShell to create the shortcut
                ps_script = f'$s = (New-Object -ComObject WScript.Shell).CreateShortcut("{self.shortcut_path}"); $s.TargetPath = "{self.target_path}"; $s.WorkingDirectory = "{os.path.dirname(self.target_path)}"; $s.Save()'
                subprocess.run(["powershell", "-Command", ps_script], check=True)
                logger.info("Autostart shortcut created.")
                return True
            except Exception as e:
                logger.error(f"Failed to create autostart shortcut: {e}")
                return False
        else:
            if not self.is_enabled():
                return True
            
            try:
                os.remove(self.shortcut_path)
                logger.info("Autostart shortcut removed.")
                return True
            except Exception as e:
                logger.error(f"Failed to remove autostart shortcut: {e}")
                return False

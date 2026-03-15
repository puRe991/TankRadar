import os
import subprocess
import logging

logger = logging.getLogger("TankRadar.Autostart")

class AutostartManager:
    def __init__(self):
        self.startup_folder = os.path.join(os.environ['APPDATA'], r"Microsoft\Windows\Start Menu\Programs\Startup")
        self.shortcut_name = "TankRadar.lnk"
        self.shortcut_path = os.path.join(self.startup_folder, self.shortcut_name)
        # Assuming we are in the project root
        self.target_path = os.path.abspath("start_tankradar.bat")

    def is_enabled(self):
        return os.path.exists(self.shortcut_path)

    def set_autostart(self, enable: bool):
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

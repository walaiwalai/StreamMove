import re


def read_config_file(config_file_path):
    """
    读取配置文件，将键值对存储在字典中
    :param config_file_path: 配置文件路径
    :return: 包含配置项的字典
    """
    config = {}
    with open(config_file_path, 'r') as file:
        for line in file:
            line = line.strip()
            if line and not line.startswith('#'):
                key, value = line.split('=', 1)
                config[key.strip()] = value.strip()
    return config


def replace_placeholders(config, java_config_file_path):
    """
    替换配置文件中的占位符
    :param config: 包含配置项的字典
    :param java_config_file_path: 待替换的配置文件路径
    """
    with open(java_config_file_path, 'r') as file:
        content = file.read()

    placeholders = re.findall(r'\$\{([^}]+)\}', content)

    for placeholder in placeholders:
        if placeholder in config:
            content = content.replace(f'${{{placeholder}}}', config[placeholder])

    with open(java_config_file_path, 'w') as file:
        file.write(content)


if __name__ == "__main__":
    config = read_config_file('/home/admin/stream/config/config.properties')
    replace_placeholders(config, 'application-prod.properties')